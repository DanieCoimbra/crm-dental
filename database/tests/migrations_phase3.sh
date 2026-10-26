#!/usr/bin/env bash
# Fase 3 / F08 (testes 117 e 118): migrations V022-V029 do zero, reversões e a função de anonimização restaurada, num banco descartável
# PRÓPRIO (phase3_scratch), para não mexer no banco dos demais testes.
#   117  V001-V029 aplicam do zero; toda V022..V029 tem seu U; as 6 tabelas existem; a cadeia U029 -> U022 reverte (nenhuma tabela, função
#        ou política da Fase 3 sobra, e as da Fase 2 ficam intactas) e V022-V029 reaplicam
#   118  a U029 restaura o comportamento da V021 (sem CR006, sem limpeza de budgets.notes nem dos PDFs); a V029 reaplicada volta a recusar
#        com CR006
# Requer: psql/openssl no PATH, PGHOST/PGPORT/PGUSER/PGPASSWORD (administrador de um servidor DESCARTÁVEL), DB_DIR e as senhas
#         CRM_OWNER_PW, CRM_APP_PW e CRM_BACKUP_PW do bootstrap do harness.
set -uo pipefail

DB_DIR="${DB_DIR:?DB_DIR nao definido}"
: "${CRM_OWNER_PW:?CRM_OWNER_PW nao definido}" "${CRM_APP_PW:?CRM_APP_PW nao definido}" "${CRM_BACKUP_PW:?CRM_BACKUP_PW nao definido}"
ADMIN_USER="$PGUSER"; ADMIN_PW="$PGPASSWORD"
SCRATCH=phase3_scratch

fail=0
ok()  { echo "  ok   - $1"; }
bad() { echo "  FAIL - $1"; fail=1; }
admin() { PGUSER="$ADMIN_USER" PGPASSWORD="$ADMIN_PW" PGDATABASE=postgres "$@"; }
admin_s() { PGUSER="$ADMIN_USER" PGPASSWORD="$ADMIN_PW" PGDATABASE=$SCRATCH "$@"; }
owner_s() { PGUSER=crm_owner PGPASSWORD="$CRM_OWNER_PW" PGDATABASE=$SCRATCH "$@"; }
app_s() { PGUSER=crm_app PGPASSWORD="$CRM_APP_PW" PGDATABASE=$SCRATCH "$@"; }

work_err="$(mktemp)"
cleanup() { rm -f "$work_err"; admin psql -X -q -c "DROP DATABASE IF EXISTS $SCRATCH WITH (FORCE)" >/dev/null 2>&1; }
trap cleanup EXIT

echo "migrations_phase3:"

mapfile -t vfiles < <(ls "$DB_DIR"/migrations/V*.sql | sort)
vnum() { echo $((10#$(basename "$1" | sed -E 's/^V0*([0-9]+)__.*/\1/'))); }
apply_range() { # $1 = primeira versão  $2 = última versão; cada migration em UMA transação, como crm_owner
  local f v
  for f in "${vfiles[@]}"; do
    v=$(vnum "$f"); [ "$v" -ge "$1" ] && [ "$v" -le "$2" ] || continue
    owner_s psql -X -q -v ON_ERROR_STOP=1 --single-transaction -f "$f" >/dev/null 2>"$work_err" || { echo "      falhou em $(basename "$f"): $(head -2 "$work_err")"; return 1; }
  done
}
# reversões na ordem inversa; $1 = maior U, $2 = menor U
revert_range() {
  local hi="$1" lo="$2" v f
  for v in $(seq "$hi" -1 "$lo"); do
    f=$(ls "$DB_DIR"/rollback/U$(printf '%03d' "$v")__*.sql 2>/dev/null | head -1)
    [ -n "$f" ] || { echo "      sem U$(printf '%03d' "$v")"; return 1; }
    owner_s psql -X -q -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>"$work_err" || { echo "      falhou em $(basename "$f"): $(head -2 "$work_err")"; return 1; }
  done
}

# --- 117: numeração e reversões ------------------------------------------------------------------------------------------------
last=$(vnum "${vfiles[${#vfiles[@]}-1]}")
[ "$last" = "29" ] && [ "${#vfiles[@]}" = "29" ] && ok "117: 29 migrations (V001..V029), a ultima e a V029" || bad "117: esperadas 29 migrations, ha ${#vfiles[@]} (ultima V$last)"
missing=""
for v in $(seq 22 29); do ls "$DB_DIR"/rollback/U$(printf '%03d' "$v")__*.sql >/dev/null 2>&1 || missing="$missing U$(printf '%03d' "$v")"; done
[ -z "$missing" ] && ok "117: toda migration da Fase 3 (V022..V029) tem seu U de reversao" || bad "117: faltam reversoes:$missing"

# --- banco descartável + bootstrap --------------------------------------------------------------------------------------------
scram() { SCRAM_PASSWORD="$1" bash "$DB_DIR/scripts/scram_verifier.sh"; }
fresh_db() { # recria o banco descartavel e roda o bootstrap dos papeis e das extensoes
  admin psql -X -q -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $SCRATCH WITH (FORCE)" -c "CREATE DATABASE $SCRATCH" >/dev/null 2>&1 || return 1
  admin_s psql -X -q -v ON_ERROR_STOP=1 -v crm_owner_scram="$(scram "$CRM_OWNER_PW")" -v crm_app_scram="$(scram "$CRM_APP_PW")" \
    -v crm_backup_scram="$(scram "$CRM_BACKUP_PW")" -f "$DB_DIR/bootstrap/00_roles_and_schema.sql" >/dev/null 2>&1 || return 1
  admin_s psql -X -q -v ON_ERROR_STOP=1 -f "$DB_DIR/bootstrap/20_extensions.sql" >/dev/null 2>&1
}
fresh_db && ok "banco descartavel $SCRATCH criado e bootstrap dos papeis e das extensoes feito" \
  || { bad "nao foi possivel criar $SCRATCH ou o bootstrap falhou"; echo "migrations_phase3: FALHOU"; exit 1; }

apply_range 1 29 && ok "117: V001-V029 aplicam do zero" || { bad "117: V001-V029 falharam"; echo "migrations_phase3: FALHOU"; exit 1; }
n_tbl=$(admin_s psql -X -At -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                                 WHERE n.nspname = 'core' AND c.relkind = 'r'
                                   AND c.relname IN ('procedure_types', 'budgets', 'budget_items', 'budget_documents', 'installments', 'payments')")
[ "$n_tbl" = "6" ] && ok "117: as 6 tabelas da Fase 3 existem" || bad "117: tabelas da Fase 3 ausentes ($n_tbl de 6)"

# --- 118: a U029 restaura o comportamento da V021 ---------------------------------------------------------------------------------
seed_patient() { # $1 sufixo  -> cria clínica/usuário/paciente/orçamento APPROVED/parcela OPEN; imprime o id do paciente
  local c u p b i pt
  c=$(admin_s psql -X -At -c "INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'p3-$1') RETURNING id" | head -1)
  u=$(admin_s psql -X -At -c "INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (gen_random_uuid(), '$c', 'Dentista', 'p3-$1@teste.local', 'x', 'DENTIST_ADMIN') RETURNING id" | head -1)
  p=$(admin_s psql -X -At -c "SELECT gen_random_uuid()"); b=$(admin_s psql -X -At -c "SELECT gen_random_uuid()")
  i=$(admin_s psql -X -At -c "SELECT gen_random_uuid()"); pt=$(admin_s psql -X -At -c "SELECT gen_random_uuid()")
  admin_s psql -X -q -v ON_ERROR_STOP=1 -c "SELECT set_config('app.clinic_id', '$c', false), set_config('app.role', 'DENTIST_ADMIN', false), set_config('app.user_id', '$u', false);
    INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES ('$p', '$c', 'Paciente $1', '1990-01-01', '11999990000', '$u');
    INSERT INTO core.procedure_types (id, clinic_id, name, base_price, default_duration_minutes, color, created_by) VALUES ('$pt', '$c', 'Proc', 100, 30, '#112233', '$u');
    INSERT INTO core.budgets (id, clinic_id, patient_id, notes, created_by) VALUES ('$b', '$c', '$p', 'Joao da Silva', '$u');
    INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position) VALUES ('$c', '$b', '$pt', 3, 1);
    UPDATE core.budgets SET status = 'SENT' WHERE id = '$b';
    INSERT INTO core.budget_documents (clinic_id, budget_id, patient_id, storage_key, content_type, size_bytes, sha256, created_by)
      VALUES ('$c', '$b', '$p', '$c/$p/budgets/' || gen_random_uuid()::text, 'application/pdf', 1000, repeat('a', 64), '$u');
    UPDATE core.budgets SET status = 'APPROVED' WHERE id = '$b';
    INSERT INTO core.installments (id, clinic_id, budget_id, patient_id, number, amount, due_date, created_by) VALUES ('$i', '$c', '$b', '$p', 1, 300, '2031-07-01', '$u');
    UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = '$p'" >/dev/null 2>&1 || return 1
  echo "$c $u $p $b"
}
anonymize() { # $1 clínica  $2 usuário  $3 paciente; imprime o SQLSTATE do erro ou OK
  local out
  out=$(app_s psql -X -At -v VERBOSITY=sqlstate -c "BEGIN; SELECT set_config('app.clinic_id', '$1', true), set_config('app.role', 'DENTIST_ADMIN', true), set_config('app.user_id', '$2', true); SELECT core.anonymize_patient('$3'); COMMIT;" 2>&1)
  if echo "$out" | grep -q 'ERROR'; then echo "$out" | grep -o 'ERROR:  [A-Z0-9]*' | head -1 | sed 's/ERROR:  //'; else echo OK; fi
}

revert_range 29 29 && ok "118: U029 reverte" || bad "118: U029 falhou"
read -r C1 U1 P1 B1 <<< "$(seed_patient restaurada)"
if [ -n "${P1:-}" ]; then
  r=$(anonymize "$C1" "$U1" "$P1")
  [ "$r" = "OK" ] && ok "118: apos a U029, parcela OPEN NAO bloqueia (comportamento da V021, sem CR006)" || bad "118: apos a U029 a anonimizacao deveria passar (obtido: $r)"
  [ "$(admin_s psql -X -At -c "SELECT notes FROM core.budgets WHERE id = '$B1'")" = "Joao da Silva" ] \
    && ok "118: apos a U029 as notes do orcamento NAO sao limpas" || bad "118: as notes do orcamento deveriam permanecer"
  [ "$(admin_s psql -X -At -c "SELECT count(*) FROM core.budget_documents WHERE patient_id = '$P1' AND deleted_at IS NOT NULL")" = "0" ] \
    && ok "118: apos a U029 os PDFs NAO sao marcados" || bad "118: os PDFs deveriam permanecer ativos"
else
  bad "118: nao foi possivel criar o paciente de teste"
fi
n_pol=$(admin_s psql -X -At -c "SELECT count(*) FROM pg_policy p WHERE (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') = ANY (p.polroles)
                                  AND p.polrelid IN ('core.budgets'::regclass, 'core.installments'::regclass, 'core.budget_documents'::regclass)")
[ "$n_pol" = "0" ] && ok "118: as politicas do crm_definer da Fase 3 saem com a U029" || bad "118: restaram $n_pol politicas do crm_definer"
[ "$(admin_s psql -X -At -c "SELECT has_table_privilege('crm_definer', 'core.installments', 'SELECT')")" = "f" ] \
  && ok "118: os grants do crm_definer da Fase 3 saem com a U029" || bad "118: crm_definer ainda le installments"

# reaplica a V029 sobre o que já existe: volta a recusar com CR006
apply_range 29 29 && ok "118: a V029 reaplica depois da U029" || bad "118: a V029 nao reaplicou"
read -r C2 U2 P2 B2 <<< "$(seed_patient reaplicada)"
if [ -n "${P2:-}" ]; then
  r=$(anonymize "$C2" "$U2" "$P2")
  [ "$r" = "CR006" ] && ok "118: com a V029 reaplicada, parcela OPEN volta a recusar com CR006" || bad "118: esperado CR006 (obtido: $r)"
fi

# --- 117: com linhas no audit_log a U028 se RECUSA (o log e so de insercao) e nao altera nada ---------------------------------------------
revert_range 28 28 >/dev/null 2>&1 && bad "117: a U028 deveria falhar com auditoria das tabelas novas no log" || ok "117: a U028 recusa reverter com linhas de auditoria das tabelas novas (restaurar do pg_dump)"
[ "$(admin_s psql -X -At -c "SELECT count(*) FROM pg_trigger WHERE tgname IN ('trg_budgets_audit', 'trg_inst_audit', 'trg_pay_audit', 'trg_bdoc_audit') AND NOT tgisinternal")" = "4" ] \
  && ok "117: a U028 recusada e atomica: os 4 triggers de auditoria continuam" || bad "117: a U028 recusada deixou a reversao pela metade"

# --- 117: a cadeia U029 -> U022 limpa a Fase 3 e preserva a Fase 2; V022-V029 reaplicam (banco novo, sem auditoria) --------------------
fresh_db && apply_range 1 29 && ok "117: banco novo com V001-V029 para a cadeia de reversao" || { bad "117: nao foi possivel recriar o banco"; echo "migrations_phase3: FALHOU"; exit 1; }
revert_range 29 22 && ok "117: a cadeia U029 -> U022 reverte" || bad "117: a cadeia U029 -> U022 falhou"
left=$(admin_s psql -X -At -c "SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'core' AND c.relkind = 'r'
                                         AND c.relname IN ('procedure_types', 'budgets', 'budget_items', 'budget_documents', 'installments', 'payments'))
                                      + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'core'
                                         AND p.proname IN ('budget_items_total', 'budgets_before_insert', 'guard_budget_update', 'guard_budget_item', 'refresh_budget_total',
                                           'budget_documents_before_insert', 'guard_budget_document_update', 'installment_net_paid', 'guard_installment',
                                           'check_installments_sum', 'guard_payment', 'sync_installment_status', 'payments_append_only'))")
[ "$left" = "0" ] && ok "117: nenhuma tabela nem funcao da Fase 3 sobra apos a cadeia" || bad "117: sobraram $left objetos da Fase 3"
[ "$(admin_s psql -X -At -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'core' AND c.relname = 'appointments'")" = "1" ] \
  && admin_s psql -X -At -c "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_audit_table'" | grep -q "appointments" \
  && ! admin_s psql -X -At -c "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_audit_table'" | grep -q "budgets" \
  && ok "117: a Fase 2 permanece (appointments existe e ck_audit_table volta a 5 tabelas)" || bad "117: a Fase 2 foi afetada pela cadeia"
[ "$(admin_s psql -X -At -c "SELECT count(*) FROM pg_constraint WHERE conname = 'fk_appt_procedure'")" = "0" ] \
  && ok "117: a FK do procedimento no agendamento saiu com a U023" || bad "117: fk_appt_procedure ainda existe"
apply_range 22 29 && ok "117: V022-V029 reaplicam depois da cadeia" || bad "117: V022-V029 nao reaplicaram"
n_tbl=$(admin_s psql -X -At -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                                 WHERE n.nspname = 'core' AND c.relkind = 'r'
                                   AND c.relname IN ('procedure_types', 'budgets', 'budget_items', 'budget_documents', 'installments', 'payments')")
[ "$n_tbl" = "6" ] && ok "117: as 6 tabelas voltam apos a reaplicacao" || bad "117: tabelas da Fase 3 ausentes apos reaplicar ($n_tbl de 6)"

[ $fail -eq 0 ] && echo "migrations_phase3: OK" || { echo "migrations_phase3: FALHOU"; exit 1; }
