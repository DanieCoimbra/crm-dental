#!/usr/bin/env bash
# Fase 2 / F02 e F08 (teste 63): conflito de horário com DUAS conexões reais como crm_app, sob RLS.
#   1. A abre transação e insere o horário H; B insere o mesmo H e FICA ESPERANDO (pg_stat_activity: wait_event_type = Lock);
#      A faz COMMIT -> B recebe 23P01 (exclusion_violation)
#   2. repete com ROLLBACK de A -> B passa
#   3. uma clínica diferente no mesmo horário NUNCA espera: termina enquanto a transação da outra clínica segue aberta
# Sincroniza por pg_stat_activity, nunca por sleep fixo. Os dados são COMMITADOS (duas conexões não enxergam transação alheia),
# então a limpeza no fim esvazia as tabelas clínicas e a auditoria; só rodar em banco DESCARTÁVEL, antes de backup_restore.sh.
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
  admin -c "DELETE FROM core.users WHERE clinic_id IN (SELECT id FROM core.clinics WHERE name LIKE 'conc-test-%')" >/dev/null 2>&1
  admin -c "DELETE FROM core.clinics WHERE name LIKE 'conc-test-%'" >/dev/null 2>&1
  rm -rf "$work"
}
trap cleanup EXIT

echo "appointments_concurrency:"

A=$(id1 -c "INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'conc-test-A') RETURNING id")
B=$(id1 -c "INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'conc-test-B') RETURNING id")
UA=$(id1 -c "INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (gen_random_uuid(), '$A', 'Dentista A', 'conc-test-a@teste.local', 'x', 'DENTIST_ADMIN') RETURNING id")
UB=$(id1 -c "INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (gen_random_uuid(), '$B', 'Dentista B', 'conc-test-b@teste.local', 'x', 'DENTIST_ADMIN') RETURNING id")
PA=$(id1 -c "SELECT gen_random_uuid()"); PB=$(id1 -c "SELECT gen_random_uuid()")
for t in "$A $UA $PA" "$B $UB $PB"; do
  set -- $t
  admin -c "SELECT set_config('app.clinic_id', '$1', false), set_config('app.role', 'DENTIST_ADMIN', false), set_config('app.user_id', '$2', false);
            INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES ('$3', '$1', 'Paciente conc', '1990-01-01', '11999990000', '$2')" >/dev/null \
    || { bad "setup dos dados falhou"; exit 1; }
done

session() { # $1 clínica  $2 usuário
  echo "BEGIN; SELECT set_config('app.clinic_id', '$1', true), set_config('app.role', 'DENTIST_ADMIN', true), set_config('app.user_id', '$2', true);"
}
insert_sql() { # $1 clínica  $2 paciente  $3 usuário  $4 início  $5 fim
  echo "INSERT INTO core.appointments (clinic_id, patient_id, starts_at, ends_at, status, created_by) VALUES ('$1', '$2', '$4', '$5', 'SCHEDULED', '$3');"
}
waiting() { # nº de sessões do crm_app esperando por lock (a conexão 2 tem application_name conc_c2)
  admin -c "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'conc_c2' AND wait_event_type = 'Lock'"
}
wait_for_lock() { # até 10 s
  local i; for i in $(seq 1 100); do [ "$(waiting)" -ge 1 ] && return 0; sleep 0.1; done; return 1
}
wait_for_pid() { # $1 = pid, até 10 s
  local i; for i in $(seq 1 100); do kill -0 "$1" 2>/dev/null || return 0; sleep 0.1; done; return 1
}
open_c1() { # abre a conexão 1 (fica aberta lendo de um FIFO); $1 clínica  $2 usuário
  rm -f "$work/c1.fifo"; mkfifo "$work/c1.fifo"
  PGAPPNAME=conc_c1 app -f "$work/c1.fifo" >"$work/c1.out" 2>&1 &
  C1PID=$!; pids="$pids $C1PID"
  exec 3>"$work/c1.fifo"
  session "$1" "$2" >&3
}
close_c1() { echo "$1" >&3; echo '\q' >&3; exec 3>&-; wait "$C1PID" 2>/dev/null; }
run_c2() { # $1 clínica  $2 usuário  $3 SQL do INSERT; em segundo plano, saída em c2.out
  { session "$1" "$2"; echo "$3"; echo "COMMIT;"; } > "$work/c2.sql"
  PGAPPNAME=conc_c2 app -f "$work/c2.sql" >"$work/c2.out" 2>&1 &
  C2PID=$!; pids="$pids $C2PID"
}

H1s='2031-06-02 10:00+00'; H1e='2031-06-02 10:30+00'
H2s='2031-06-03 10:00+00'; H2e='2031-06-03 10:30+00'
H3s='2031-06-04 10:00+00'; H3e='2031-06-04 10:30+00'

# --- 1. COMMIT de A: B espera e recebe 23P01 ----------------------------------------------------------------------------------
open_c1 "$A" "$UA"; insert_sql "$A" "$PA" "$UA" "$H1s" "$H1e" >&3
sleep 0.3
run_c2 "$A" "$UA" "$(insert_sql "$A" "$PA" "$UA" "$H1s" "$H1e")"
if wait_for_lock; then ok "63: a segunda insercao do mesmo horario fica ESPERANDO (wait_event_type = Lock)"; else bad "63: a segunda conexao nao ficou esperando"; fi
close_c1 "COMMIT;"
wait_for_pid "$C2PID" || bad "63: a conexao 2 nao terminou apos o COMMIT da conexao 1"
grep -q '23P01' "$work/c2.out" && ok "63: apos o COMMIT de A, a conexao 2 recebe 23P01" || bad "63: esperado 23P01 na conexao 2: $(head -3 "$work/c2.out")"
[ "$(admin -c "SELECT count(*) FROM core.appointments WHERE clinic_id = '$A' AND starts_at = '$H1s'")" = "1" ] \
  && ok "63: ficou um unico agendamento no horario" || bad "63: quantidade de agendamentos no horario divergente"

# --- 2. ROLLBACK de A: B passa -------------------------------------------------------------------------------------------------
open_c1 "$A" "$UA"; insert_sql "$A" "$PA" "$UA" "$H2s" "$H2e" >&3
sleep 0.3
run_c2 "$A" "$UA" "$(insert_sql "$A" "$PA" "$UA" "$H2s" "$H2e")"
if wait_for_lock; then ok "63: com A ainda aberta, a conexao 2 espera"; else bad "63: a conexao 2 nao esperou (ROLLBACK)"; fi
close_c1 "ROLLBACK;"
wait_for_pid "$C2PID" || bad "63: a conexao 2 nao terminou apos o ROLLBACK da conexao 1"
! grep -q 'ERROR' "$work/c2.out" && [ "$(admin -c "SELECT count(*) FROM core.appointments WHERE clinic_id = '$A' AND starts_at = '$H2s'")" = "1" ] \
  && ok "63: apos o ROLLBACK de A, a conexao 2 insere o horario" || bad "63: a conexao 2 deveria passar apos o ROLLBACK: $(head -3 "$work/c2.out")"

# --- 3. clínica diferente nunca espera ------------------------------------------------------------------------------------------
open_c1 "$A" "$UA"; insert_sql "$A" "$PA" "$UA" "$H3s" "$H3e" >&3
sleep 0.3
run_c2 "$B" "$UB" "$(insert_sql "$B" "$PB" "$UB" "$H3s" "$H3e")"
if wait_for_pid "$C2PID"; then
  ! grep -q 'ERROR' "$work/c2.out" && [ "$(waiting)" = "0" ] && ok "63: a clinica B no mesmo horario termina sem esperar enquanto A segue aberta" \
    || bad "63: a clinica B esperou ou falhou: $(head -3 "$work/c2.out")"
else
  bad "63: a clinica B ficou presa esperando a transacao da clinica A"
fi
close_c1 "ROLLBACK;"

[ $fail -eq 0 ] && echo "appointments_concurrency: OK" || { echo "appointments_concurrency: FALHOU"; exit 1; }
