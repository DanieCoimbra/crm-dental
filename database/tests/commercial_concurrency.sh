#!/usr/bin/env bash
# Fase 3 / F08 (testes 91, 99, 107 e 110): concorrência do comercial e do financeiro com DUAS conexões reais como crm_app, sob RLS.
#   91   numeração: dois orçamentos simultâneos na mesma clínica recebem números distintos e sequenciais (a 2ª espera o lock consultivo da
#        clínica); outra clínica nunca espera
#   99   item x envio: item inserido ANTES do envio entra no total; item depois do envio falha com CR007 (o orçamento é o lock)
#   107  pagamento no último saldo: o 2º espera a trava da parcela e, se o 1º confirma, recebe CR010; com ROLLBACK do 1º passa;
#        parcelas diferentes nunca se esperam
#   110  PDF: duas revisões simultâneas do mesmo orçamento recebem revisões 1 e 2
# Sincroniza por pg_stat_activity, nunca por sleep fixo. Os dados são COMMITADOS (duas conexões não enxergam transação alheia), então a
# limpeza no fim esvazia as tabelas e a auditoria; só rodar em banco DESCARTÁVEL, antes de backup_restore.sh.
# Requer: psql, mkfifo; PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE (administrador) e CRM_APP_PW.
set -uo pipefail

: "${CRM_APP_PW:?CRM_APP_PW nao definido}"
ADMIN_USER="$PGUSER"; ADMIN_PW="$PGPASSWORD"
admin() { PGUSER="$ADMIN_USER" PGPASSWORD="$ADMIN_PW" psql -X -At -v ON_ERROR_STOP=1 "$@"; }
app() { PGUSER=crm_app PGPASSWORD="$CRM_APP_PW" psql -X -At -v VERBOSITY=sqlstate "$@"; }
id1() { admin "$@" | head -1; }

fail=0
ok()  { echo "  ok   - $1"; }
bad() { echo "  FAIL - $1"; fail=1; }

work="$(mktemp -d)"
pids=""
cleanup() {
  exec 3>&- 2>/dev/null || true
  for p in $pids; do kill "$p" 2>/dev/null; done
  admin -c "ALTER TABLE core.audit_log DISABLE TRIGGER trg_audit_log_no_truncate; ALTER TABLE core.payments DISABLE TRIGGER trg_pay_no_truncate; ALTER TABLE core.clinical_documents DISABLE TRIGGER trg_cdoc_no_truncate; TRUNCATE core.audit_log, core.clinical_documents, core.payments, core.installments, core.budget_documents, core.budget_items, core.budgets, core.appointments, core.procedure_types, core.business_hours, core.clinic_holidays, core.patient_files, core.clinical_notes, core.medical_histories, core.patients; ALTER TABLE core.audit_log ENABLE TRIGGER trg_audit_log_no_truncate; ALTER TABLE core.payments ENABLE TRIGGER trg_pay_no_truncate; ALTER TABLE core.clinical_documents ENABLE TRIGGER trg_cdoc_no_truncate" >/dev/null 2>&1
  admin -c "DELETE FROM core.users WHERE clinic_id IN (SELECT id FROM core.clinics WHERE name LIKE 'conc3-test-%')" >/dev/null 2>&1
  admin -c "DELETE FROM core.clinics WHERE name LIKE 'conc3-test-%'" >/dev/null 2>&1
  rm -rf "$work"
}
trap cleanup EXIT

echo "commercial_concurrency:"

# --- dados (commitados): duas clínicas, usuário, paciente e procedimento de cada uma --------------------------------------------------
A=$(id1 -c "INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'conc3-test-A') RETURNING id")
B=$(id1 -c "INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'conc3-test-B') RETURNING id")
UA=$(id1 -c "INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (gen_random_uuid(), '$A', 'Dentista A', 'conc3-test-a@teste.local', 'x', 'DENTIST_ADMIN') RETURNING id")
UB=$(id1 -c "INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (gen_random_uuid(), '$B', 'Dentista B', 'conc3-test-b@teste.local', 'x', 'DENTIST_ADMIN') RETURNING id")
PA=$(id1 -c "SELECT gen_random_uuid()"); PB=$(id1 -c "SELECT gen_random_uuid()")
TA=$(id1 -c "SELECT gen_random_uuid()"); TB=$(id1 -c "SELECT gen_random_uuid()")
for t in "$A $UA $PA $TA" "$B $UB $PB $TB"; do
  set -- $t
  admin -c "SELECT set_config('app.clinic_id', '$1', false), set_config('app.role', 'DENTIST_ADMIN', false), set_config('app.user_id', '$2', false);
            INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES ('$3', '$1', 'Paciente conc3', '1990-01-01', '11999990000', '$2');
            INSERT INTO core.procedure_types (id, clinic_id, name, base_price, default_duration_minutes, color, created_by) VALUES ('$4', '$1', 'Proc conc3', 100.00, 30, '#112233', '$2')" >/dev/null \
    || { bad "setup dos dados falhou"; exit 1; }
done

admin_ctx() { # $1 clínica  $2 usuário  -> prefixo SQL que define a sessão do administrador (sem RLS) para a auditoria
  echo "SELECT set_config('app.clinic_id', '$1', false), set_config('app.role', 'DENTIST_ADMIN', false), set_config('app.user_id', '$2', false);"
}
# orçamento APPROVED (3 x 100 = 300) com uma parcela de 300 e 200 já pagos (saldo 100); devolve o id da parcela
mk_installment() {
  local b i
  b=$(id1 -c "SELECT gen_random_uuid()"); i=$(id1 -c "SELECT gen_random_uuid()")
  admin -c "$(admin_ctx "$A" "$UA")
            INSERT INTO core.budgets (id, clinic_id, patient_id, created_by) VALUES ('$b', '$A', '$PA', '$UA');
            INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position) VALUES ('$A', '$b', '$TA', 3, 1);
            UPDATE core.budgets SET status = 'APPROVED' WHERE id = '$b';
            INSERT INTO core.installments (id, clinic_id, budget_id, patient_id, number, amount, due_date, created_by) VALUES ('$i', '$A', '$b', '$PA', 1, 300, '2031-07-01', '$UA');
            INSERT INTO core.payments (clinic_id, installment_id, kind, amount, method, paid_on, created_by) VALUES ('$A', '$i', 'PAYMENT', 200, 'PIX', '2031-07-02', '$UA')" >/dev/null \
    || { bad "setup da parcela falhou"; exit 1; }
  echo "$i"
}
# orçamento DRAFT com 1 item (100); devolve o id
mk_draft() {
  local b; b=$(id1 -c "SELECT gen_random_uuid()")
  admin -c "$(admin_ctx "$A" "$UA")
            INSERT INTO core.budgets (id, clinic_id, patient_id, created_by) VALUES ('$b', '$A', '$PA', '$UA');
            INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position) VALUES ('$A', '$b', '$TA', 1, 1)" >/dev/null \
    || { bad "setup do orcamento falhou"; exit 1; }
  echo "$b"
}

session() { # $1 clínica  $2 usuário
  echo "BEGIN; SELECT set_config('app.clinic_id', '$1', true), set_config('app.role', 'DENTIST_ADMIN', true), set_config('app.user_id', '$2', true);"
}
waiting() { # nº de sessões do crm_app esperando por lock (a conexão 2 tem application_name conc_c2)
  admin -c "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'conc_c2' AND wait_event_type = 'Lock'"
}
wait_for_lock() { local i; for i in $(seq 1 100); do [ "$(waiting)" -ge 1 ] && return 0; sleep 0.1; done; return 1; }
wait_for_pid()  { local i; for i in $(seq 1 100); do kill -0 "$1" 2>/dev/null || return 0; sleep 0.1; done; return 1; }
open_c1() { # abre a conexão 1 (fica aberta lendo de um FIFO); $1 clínica  $2 usuário
  rm -f "$work/c1.fifo"; mkfifo "$work/c1.fifo"
  PGAPPNAME=conc_c1 app -f "$work/c1.fifo" >"$work/c1.out" 2>&1 &
  C1PID=$!; pids="$pids $C1PID"
  exec 3>"$work/c1.fifo"
  session "$1" "$2" >&3
}
close_c1() { echo "$1" >&3; echo '\q' >&3; exec 3>&-; wait "$C1PID" 2>/dev/null; }
run_c2() { # $1 clínica  $2 usuário  $3 SQL; em segundo plano, saída em c2.out
  { session "$1" "$2"; echo "$3"; echo "COMMIT;"; } > "$work/c2.sql"
  PGAPPNAME=conc_c2 app -f "$work/c2.sql" >"$work/c2.out" 2>&1 &
  C2PID=$!; pids="$pids $C2PID"
}

# =====================================================================================================================================
# 91: numeração do orçamento
# =====================================================================================================================================
BN1=$(id1 -c "SELECT gen_random_uuid()"); BN2=$(id1 -c "SELECT gen_random_uuid()"); BNB=$(id1 -c "SELECT gen_random_uuid()")
ins_budget() { echo "INSERT INTO core.budgets (id, clinic_id, patient_id, created_by) VALUES ('$1', '$2', '$3', '$4');"; }
BEFORE_A=$(id1 -c "SELECT COALESCE(max(number), 0) FROM core.budgets WHERE clinic_id = '$A'")
open_c1 "$A" "$UA"; ins_budget "$BN1" "$A" "$PA" "$UA" >&3
sleep 0.3
run_c2 "$A" "$UA" "$(ins_budget "$BN2" "$A" "$PA" "$UA")"
if wait_for_lock; then ok "91: o 2o INSERT de orcamento da mesma clinica fica ESPERANDO (lock consultivo, wait_event_type = Lock)"; else bad "91: a conexao 2 nao ficou esperando"; fi
# outra clínica no mesmo instante NÃO espera: termina enquanto a transação da clínica A segue aberta
run_b() { { session "$B" "$UB"; ins_budget "$BNB" "$B" "$PB" "$UB"; echo "COMMIT;"; } > "$work/cb.sql"; PGAPPNAME=conc_cb app -f "$work/cb.sql" >"$work/cb.out" 2>&1 & CBPID=$!; pids="$pids $CBPID"; }
run_b
if wait_for_pid "$CBPID"; then
  ! grep -q 'ERROR' "$work/cb.out" && [ "$(id1 -c "SELECT number FROM core.budgets WHERE id = '$BNB'")" = "1" ] \
    && ok "91: a clinica B nao espera a clinica A (numero 1 na B)" || bad "91: a clinica B falhou: $(head -3 "$work/cb.out")"
else
  bad "91: a clinica B ficou presa esperando a transacao da clinica A"
fi
close_c1 "COMMIT;"
wait_for_pid "$C2PID" || bad "91: a conexao 2 nao terminou apos o COMMIT da conexao 1"
N1=$(id1 -c "SELECT number FROM core.budgets WHERE id = '$BN1'"); N2=$(id1 -c "SELECT number FROM core.budgets WHERE id = '$BN2'")
{ [ "$N1" = "$((BEFORE_A + 1))" ] && [ "$N2" = "$((BEFORE_A + 2))" ]; } \
  && ok "91: numeros distintos e sequenciais ($N1 e $N2) apos o COMMIT da conexao 1" || bad "91: numeros inesperados: $N1 e $N2 (antes: $BEFORE_A)"

# =====================================================================================================================================
# 99: item x envio (o orçamento é o lock)
# =====================================================================================================================================
# (a) o item entra ANTES: a conexão 2 (envio) espera, e o envio vê o item novo
BX=$(mk_draft)
open_c1 "$A" "$UA"
echo "INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position) VALUES ('$A', '$BX', '$TA', 2, 2);" >&3
sleep 0.3
run_c2 "$A" "$UA" "UPDATE core.budgets SET status = 'SENT' WHERE id = '$BX';"
if wait_for_lock; then ok "99a: o envio ESPERA a transacao que esta inserindo item (trava do orcamento)"; else bad "99a: o envio nao esperou"; fi
close_c1 "COMMIT;"
wait_for_pid "$C2PID" || bad "99a: o envio nao terminou apos o COMMIT do item"
if ! grep -q 'ERROR' "$work/c2.out" \
   && [ "$(id1 -c "SELECT status || ':' || total_amount FROM core.budgets WHERE id = '$BX'")" = "SENT:300.00" ]; then
  ok "99a: o envio terminou com o item novo no total (SENT, 100 + 200 = 300)"
else
  bad "99a: estado inesperado: $(id1 -c "SELECT status || ':' || total_amount FROM core.budgets WHERE id = '$BX'") $(head -3 "$work/c2.out")"
fi
# (b) o envio vem ANTES: o item falha com CR007
BY=$(mk_draft)
open_c1 "$A" "$UA"
echo "UPDATE core.budgets SET status = 'SENT' WHERE id = '$BY';" >&3
sleep 0.3
run_c2 "$A" "$UA" "INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position) VALUES ('$A', '$BY', '$TA', 2, 2);"
if wait_for_lock; then ok "99b: o item ESPERA a transacao que esta enviando o orcamento"; else bad "99b: o item nao esperou"; fi
close_c1 "COMMIT;"
wait_for_pid "$C2PID" || bad "99b: o item nao terminou apos o COMMIT do envio"
if grep -q 'CR007' "$work/c2.out" && [ "$(id1 -c "SELECT count(*) FROM core.budget_items WHERE budget_id = '$BY'")" = "1" ] \
   && [ "$(id1 -c "SELECT total_amount FROM core.budgets WHERE id = '$BY'")" = "100.00" ]; then
  ok "99b: depois do envio o item recebe CR007 e o total fica em 100"
else
  bad "99b: esperado CR007: $(head -3 "$work/c2.out")"
fi

# =====================================================================================================================================
# 107: pagamento no último saldo
# =====================================================================================================================================
pay_sql() { echo "INSERT INTO core.payments (clinic_id, installment_id, kind, amount, method, paid_on, created_by) VALUES ('$A', '$1', 'PAYMENT', 100, 'PIX', '2031-07-03', '$UA');"; }
IPa=$(mk_installment); IPb=$(mk_installment); IPc=$(mk_installment); IPd=$(mk_installment)
# (a) COMMIT de A: B espera e recebe CR010
open_c1 "$A" "$UA"; pay_sql "$IPa" >&3
sleep 0.3
run_c2 "$A" "$UA" "$(pay_sql "$IPa")"
if wait_for_lock; then ok "107: o 2o pagamento do ultimo saldo fica ESPERANDO (trava da parcela)"; else bad "107: a conexao 2 nao esperou"; fi
close_c1 "COMMIT;"
wait_for_pid "$C2PID" || bad "107: a conexao 2 nao terminou apos o COMMIT da conexao 1"
grep -q 'CR010' "$work/c2.out" && ok "107: apos o COMMIT de A, o 2o pagamento recebe CR010" || bad "107: esperado CR010: $(head -3 "$work/c2.out")"
[ "$(id1 -c "SELECT status || ':' || (SELECT count(*) FROM core.payments WHERE installment_id = '$IPa') FROM core.installments WHERE id = '$IPa'")" = "PAID:2" ] \
  && ok "107: a parcela ficou PAID com 2 pagamentos (200 + 100), sem pagamento a maior" || bad "107: estado final da parcela divergente"
# (b) ROLLBACK de A: B passa
open_c1 "$A" "$UA"; pay_sql "$IPb" >&3
sleep 0.3
run_c2 "$A" "$UA" "$(pay_sql "$IPb")"
if wait_for_lock; then ok "107: com A ainda aberta, a conexao 2 espera"; else bad "107: a conexao 2 nao esperou (ROLLBACK)"; fi
close_c1 "ROLLBACK;"
wait_for_pid "$C2PID" || bad "107: a conexao 2 nao terminou apos o ROLLBACK da conexao 1"
! grep -q 'ERROR' "$work/c2.out" && [ "$(id1 -c "SELECT status FROM core.installments WHERE id = '$IPb'")" = "PAID" ] \
  && ok "107: apos o ROLLBACK de A, o pagamento da conexao 2 passa e quita a parcela" || bad "107: deveria passar apos o ROLLBACK: $(head -3 "$work/c2.out")"
# (c) parcelas diferentes nunca se esperam
open_c1 "$A" "$UA"; pay_sql "$IPc" >&3
sleep 0.3
run_c2 "$A" "$UA" "$(pay_sql "$IPd")"
if wait_for_pid "$C2PID"; then
  ! grep -q 'ERROR' "$work/c2.out" && [ "$(waiting)" = "0" ] && ok "107: pagamento em OUTRA parcela termina sem esperar enquanto a 1a transacao segue aberta" \
    || bad "107: o pagamento em outra parcela esperou ou falhou: $(head -3 "$work/c2.out")"
else
  bad "107: o pagamento em outra parcela ficou preso esperando"
fi
close_c1 "ROLLBACK;"

# =====================================================================================================================================
# 110: revisões do PDF
# =====================================================================================================================================
BD=$(mk_draft)
admin -c "$(admin_ctx "$A" "$UA") UPDATE core.budgets SET status = 'SENT' WHERE id = '$BD'" >/dev/null || bad "110: setup do envio falhou"
doc_sql() { echo "INSERT INTO core.budget_documents (clinic_id, budget_id, patient_id, storage_key, content_type, size_bytes, sha256, created_by) VALUES ('$A', '$BD', '$PA', '$A/$PA/budgets/' || gen_random_uuid()::text, 'application/pdf', 1000, repeat('a', 64), '$UA');"; }
open_c1 "$A" "$UA"; doc_sql >&3
sleep 0.3
run_c2 "$A" "$UA" "$(doc_sql)"
if wait_for_lock; then ok "110: a 2a revisao do mesmo orcamento fica ESPERANDO (trava do orcamento)"; else bad "110: a conexao 2 nao esperou"; fi
close_c1 "COMMIT;"
wait_for_pid "$C2PID" || bad "110: a conexao 2 nao terminou apos o COMMIT da conexao 1"
! grep -q 'ERROR' "$work/c2.out" && [ "$(id1 -c "SELECT string_agg(revision::text, ',' ORDER BY revision) FROM core.budget_documents WHERE budget_id = '$BD'")" = "1,2" ] \
  && ok "110: as duas revisoes simultaneas recebem 1 e 2" || bad "110: revisoes inesperadas: $(id1 -c "SELECT string_agg(revision::text, ',' ORDER BY revision) FROM core.budget_documents WHERE budget_id = '$BD'") $(head -3 "$work/c2.out")"

[ $fail -eq 0 ] && echo "commercial_concurrency: OK" || { echo "commercial_concurrency: FALHOU"; exit 1; }
