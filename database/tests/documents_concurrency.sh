#!/usr/bin/env bash
# Fase 4 / F02 (teste 131): numeração de documentos clínicos com DUAS conexões reais como crm_app, sob RLS.
#   131a  duas emissões simultâneas do mesmo tipo na mesma clínica recebem números distintos e consecutivos (a 2ª espera o lock
#         consultivo da clínica e do tipo até o COMMIT da 1ª)
#   131b  o ROLLBACK da 1ª libera a 2ª, que recebe o mesmo número que a 1ª teria tido (sem buraco, sem repetição)
#   131c  tipos diferentes na mesma clínica e clínicas diferentes nunca se esperam
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
  admin -c "DELETE FROM core.users WHERE clinic_id IN (SELECT id FROM core.clinics WHERE name LIKE 'conc4-test-%')" >/dev/null 2>&1
  admin -c "DELETE FROM core.clinics WHERE name LIKE 'conc4-test-%'" >/dev/null 2>&1
  rm -rf "$work"
}
trap cleanup EXIT

echo "documents_concurrency:"

# --- dados (commitados): duas clínicas, dentista com CRO e paciente de cada uma ---------------------------------------------------
A=$(id1 -c "INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'conc4-test-A') RETURNING id")
B=$(id1 -c "INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'conc4-test-B') RETURNING id")
UA=$(id1 -c "INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (gen_random_uuid(), '$A', 'Dentista A', 'conc4-test-a@teste.local', 'x', 'DENTIST_ADMIN') RETURNING id")
UB=$(id1 -c "INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (gen_random_uuid(), '$B', 'Dentista B', 'conc4-test-b@teste.local', 'x', 'DENTIST_ADMIN') RETURNING id")
PA=$(id1 -c "SELECT gen_random_uuid()"); PB=$(id1 -c "SELECT gen_random_uuid()")
for t in "$A $UA $PA" "$B $UB $PB"; do
  set -- $t
  admin -c "SELECT set_config('app.clinic_id', '$1', false), set_config('app.role', 'DENTIST_ADMIN', false), set_config('app.user_id', '$2', false);
            UPDATE core.users SET cro_number = '12345', cro_state = 'SP' WHERE id = '$2';
            INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES ('$3', '$1', 'Paciente conc4', '1990-01-01', '11999990000', '$2')" >/dev/null \
    || { bad "setup dos dados falhou"; exit 1; }
done

session() { # $1 clínica  $2 usuário
  echo "BEGIN; SELECT set_config('app.clinic_id', '$1', true), set_config('app.role', 'DENTIST_ADMIN', true), set_config('app.user_id', '$2', true);"
}
ins() { # $1 clínica  $2 paciente  $3 usuário  $4 tipo  -> INSERT que devolve o número
  local extra="NULL" spec="NULL"
  [ "$4" = CERTIFICATE ] && extra=5
  [ "$4" = REFERRAL ] && spec="'Endodontia'"
  echo "INSERT INTO core.clinical_documents (clinic_id, patient_id, document_type, issued_on, rest_days, target_specialty, content, sha256, created_by) VALUES ('$1', '$2', '$4', DATE '2031-07-01', $extra, $spec, repeat('QUJD', 20), repeat('a', 64), '$3') RETURNING number;"
}
waiting() { admin -c "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'conc_c2' AND wait_event_type = 'Lock'"; }
wait_for_lock() { local i; for i in $(seq 1 100); do [ "$(waiting)" -ge 1 ] && return 0; sleep 0.1; done; return 1; }
wait_for_pid()  { local i; for i in $(seq 1 100); do kill -0 "$1" 2>/dev/null || return 0; sleep 0.1; done; return 1; }

open_c1() { # abre a conexão 1 (fica aberta lendo de um FIFO)
  rm -f "$work/c1.fifo"; mkfifo "$work/c1.fifo"
  PGAPPNAME=conc_c1 app -f "$work/c1.fifo" >"$work/c1.out" 2>&1 &
  C1PID=$!; pids="$pids $C1PID"
  exec 3>"$work/c1.fifo"
}
close_c1() { exec 3>&-; wait_for_pid "$C1PID"; }

nowait() { # $1 arquivo de saida; demais: SQL. Roda numa conexao propria e espera ate 5 s: se travar no lock, devolve vazio
  PGAPPNAME=conc_c3 app -c "$2" >"$1" 2>&1 &
  local p=$!; pids="$pids $p"
  wait_for_pid "$p" || { kill "$p" 2>/dev/null; return 1; }
  grep -E '^[0-9]+$' "$1" | head -1
}
# --- 131a: a 2ª espera a 1ª e recebe o número seguinte -----------------------------------------------------------------------------
open_c1
{ session "$A" "$UA"; ins "$A" "$PA" "$UA" CERTIFICATE; } >&3
sleep 0.5
PGAPPNAME=conc_c2 app -c "$(session "$A" "$UA") $(ins "$A" "$PA" "$UA" CERTIFICATE) COMMIT;" >"$work/c2.out" 2>&1 &
C2PID=$!; pids="$pids $C2PID"
wait_for_lock && ok "131a: a 2a emissao do mesmo tipo e da mesma clinica espera o lock da 1a" || bad "131a: a 2a emissao nao esperou o lock"
echo "COMMIT;" >&3
close_c1
wait_for_pid "$C2PID"
n1=$(grep -E '^[0-9]+$' "$work/c1.out" | head -1); n2=$(grep -E '^[0-9]+$' "$work/c2.out" | head -1)
[ "$n1" = "1" ] && [ "$n2" = "2" ] && ok "131a: numeros 1 e 2 (distintos e consecutivos)" || bad "131a: esperados 1 e 2, obtidos '$n1' e '$n2'"

# --- 131b: ROLLBACK da 1ª libera a 2ª com o mesmo número ---------------------------------------------------------------------------
open_c1
{ session "$A" "$UA"; ins "$A" "$PA" "$UA" CERTIFICATE; } >&3
sleep 0.5
PGAPPNAME=conc_c2 app -c "$(session "$A" "$UA") $(ins "$A" "$PA" "$UA" CERTIFICATE) COMMIT;" >"$work/c2.out" 2>&1 &
C2PID=$!; pids="$pids $C2PID"
wait_for_lock && ok "131b: a 2a emissao espera a 1a" || bad "131b: a 2a emissao nao esperou"
echo "ROLLBACK;" >&3
close_c1
wait_for_pid "$C2PID"
n1=$(grep -E '^[0-9]+$' "$work/c1.out" | head -1); n2=$(grep -E '^[0-9]+$' "$work/c2.out" | head -1)
[ "$n1" = "3" ] && [ "$n2" = "3" ] && ok "131b: a 1a recebeu 3 (desfeito) e a 2a recebeu o mesmo 3: sem buraco e sem repeticao" || bad "131b: esperados 3 e 3, obtidos '$n1' e '$n2'"
[ "$(admin -c "SELECT count(*) FROM core.clinical_documents WHERE clinic_id = '$A' AND document_type = 'CERTIFICATE'")" = "3" ] \
  && ok "131b: a clinica A tem 3 atestados confirmados" || bad "131b: contagem de atestados incorreta"

# --- 131c: tipos diferentes e clínicas diferentes nunca se esperam -----------------------------------------------------------------
open_c1
{ session "$A" "$UA"; ins "$A" "$PA" "$UA" CERTIFICATE; } >&3
sleep 0.5
r=$(nowait "$work/c3.out" "$(session "$A" "$UA") $(ins "$A" "$PA" "$UA" PRESCRIPTION) COMMIT;")
[ "$r" = "1" ] && ok "131c: outro tipo na mesma clinica nao espera (receita 1)" || bad "131c: receita deveria sair com o numero 1 sem esperar (obtido '$r')"
r=$(nowait "$work/c4.out" "$(session "$B" "$UB") $(ins "$B" "$PB" "$UB" CERTIFICATE) COMMIT;")
[ "$r" = "1" ] && ok "131c: outra clinica nao espera (atestado 1 da clinica B)" || bad "131c: atestado da clinica B deveria sair com o numero 1 sem esperar (obtido '$r')"
echo "ROLLBACK;" >&3
close_c1

[ $fail -eq 0 ] && echo "documents_concurrency: OK" || { echo "documents_concurrency: FALHOU"; exit 1; }
