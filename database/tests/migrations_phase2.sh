#!/usr/bin/env bash
# Fase 2 / F08 (testes 82 e 83): aplicação das migrations V014-V021 do zero e guarda do btree_gist, num banco descartável
# PRÓPRIO (phase2_scratch), para não mexer no banco dos demais testes.
#   82  V001-V013 + V014-V021 aplicam do zero; a quantidade de migrations é MIGRATIONS_EXPECTED, a numeração é contínua e
#       cada V tem seu U (reversão); a cadeia de reversão U021 -> U014 fica em rollback_chain.sh
#   83  sem btree_gist a V015 aborta com a mensagem do guarda (e não deixa a tabela); com o bootstrap reexecutado, aplica;
#       o bootstrap 20_extensions.sql é idempotente (duas execuções)
# Requer: psql/openssl no PATH, PGHOST/PGPORT/PGUSER/PGPASSWORD (administrador de um servidor DESCARTÁVEL), DB_DIR,
#         MIGRATIONS_EXPECTED e as senhas CRM_OWNER_PW, CRM_APP_PW e CRM_BACKUP_PW do bootstrap do harness.
set -uo pipefail

DB_DIR="${DB_DIR:?DB_DIR nao definido}"
: "${MIGRATIONS_EXPECTED:?MIGRATIONS_EXPECTED nao definido}"
: "${CRM_OWNER_PW:?CRM_OWNER_PW nao definido}" "${CRM_APP_PW:?CRM_APP_PW nao definido}" "${CRM_BACKUP_PW:?CRM_BACKUP_PW nao definido}"
ADMIN_USER="$PGUSER"; ADMIN_PW="$PGPASSWORD"
SCRATCH=phase2_scratch

fail=0
ok()  { echo "  ok   - $1"; }
bad() { echo "  FAIL - $1"; fail=1; }
admin() { PGUSER="$ADMIN_USER" PGPASSWORD="$ADMIN_PW" PGDATABASE=postgres "$@"; }
admin_s() { PGUSER="$ADMIN_USER" PGPASSWORD="$ADMIN_PW" PGDATABASE=$SCRATCH "$@"; }
owner_s() { PGUSER=crm_owner PGPASSWORD="$CRM_OWNER_PW" PGDATABASE=$SCRATCH "$@"; }

cleanup() { admin psql -X -q -c "DROP DATABASE IF EXISTS $SCRATCH WITH (FORCE)" >/dev/null 2>&1; }
trap cleanup EXIT

echo "migrations_phase2:"

# --- 82: contagem e numeração das migrations --------------------------------------------------------------------------------
mapfile -t vfiles < <(ls "$DB_DIR"/migrations/V*.sql | sort)
[ "${#vfiles[@]}" = "$MIGRATIONS_EXPECTED" ] && ok "82: ${#vfiles[@]} migrations (MIGRATIONS_EXPECTED=$MIGRATIONS_EXPECTED)" \
  || bad "82: ${#vfiles[@]} migrations, esperado $MIGRATIONS_EXPECTED"
n=0; seq_ok=1
for f in "${vfiles[@]}"; do
  n=$((n + 1)); v=$((10#$(basename "$f" | sed -E 's/^V0*([0-9]+)__.*/\1/')))
  [ "$v" = "$n" ] || seq_ok=0
done
[ $seq_ok = 1 ] && ok "82: numeracao V001..V$(printf '%03d' "$n") continua e sem repeticao" || bad "82: numeracao das migrations tem buraco ou repeticao"
missing=""
for v in $(seq 14 "$MIGRATIONS_EXPECTED"); do
  ls "$DB_DIR"/rollback/U$(printf '%03d' "$v")__*.sql >/dev/null 2>&1 || missing="$missing U$(printf '%03d' "$v")"
done
[ -z "$missing" ] && ok "82: toda migration da Fase 2 (V014..V0$MIGRATIONS_EXPECTED) tem seu U de reversao" || bad "82: faltam reversoes:$missing"

# --- banco descartável + bootstrap -------------------------------------------------------------------------------------------
admin psql -X -q -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $SCRATCH WITH (FORCE)" -c "CREATE DATABASE $SCRATCH" >/dev/null 2>&1 \
  && ok "banco descartavel $SCRATCH criado" || { bad "nao foi possivel criar $SCRATCH"; echo "migrations_phase2: FALHOU"; exit 1; }
scram() { SCRAM_PASSWORD="$1" bash "$DB_DIR/scripts/scram_verifier.sh"; }
admin_s psql -X -q -v ON_ERROR_STOP=1 -v crm_owner_scram="$(scram "$CRM_OWNER_PW")" -v crm_app_scram="$(scram "$CRM_APP_PW")" \
  -v crm_backup_scram="$(scram "$CRM_BACKUP_PW")" -f "$DB_DIR/bootstrap/00_roles_and_schema.sql" >/dev/null 2>&1 \
  && ok "bootstrap dos papeis no banco descartavel" || { bad "bootstrap 00 falhou"; echo "migrations_phase2: FALHOU"; exit 1; }

apply_range() { # $1 = primeira versão  $2 = última versão; cada migration em UMA transação, como crm_owner
  local f v
  for f in "${vfiles[@]}"; do
    v=$((10#$(basename "$f" | sed -E 's/^V0*([0-9]+)__.*/\1/')))
    [ "$v" -ge "$1" ] && [ "$v" -le "$2" ] || continue
    owner_s psql -X -q -v ON_ERROR_STOP=1 --single-transaction -f "$f" >/dev/null 2>"$work_err" || return 1
  done
}
work_err="$(mktemp)"; trap 'rm -f "$work_err"; cleanup' EXIT

# --- 83: sem btree_gist ------------------------------------------------------------------------------------------------------
admin_s psql -X -q -v ON_ERROR_STOP=1 -f "$DB_DIR/bootstrap/20_extensions.sql" >/dev/null 2>&1 \
  && ok "83: 20_extensions: primeira execucao" || bad "83: 20_extensions falhou"
admin_s psql -X -q -v ON_ERROR_STOP=1 -f "$DB_DIR/bootstrap/20_extensions.sql" >/dev/null 2>&1 \
  && ok "83: 20_extensions: segunda execucao (idempotente)" || bad "83: 20_extensions nao e idempotente"
admin_s psql -X -q -v ON_ERROR_STOP=1 -c "DROP EXTENSION btree_gist" >/dev/null 2>&1 || bad "83: nao foi possivel remover btree_gist para o teste"

apply_range 1 14 && ok "82: V001-V014 aplicam do zero sem btree_gist (a V014 nao depende dela)" || bad "82: V001-V014 falharam: $(head -2 "$work_err")"
if apply_range 15 15; then
  bad "83: a V015 aplicou sem btree_gist"
else
  grep -q 'btree_gist ausente' "$work_err" && ok "83: sem btree_gist a V015 aborta com a mensagem do guarda" \
    || bad "83: a V015 falhou por outro motivo: $(head -2 "$work_err")"
fi
[ "$(admin_s psql -X -At -c "SELECT to_regclass('core.appointments') IS NULL")" = "t" ] \
  && ok "83: a V015 abortada nao deixou a tabela appointments" || bad "83: a tabela appointments existe apos o aborto"

admin_s psql -X -q -v ON_ERROR_STOP=1 -f "$DB_DIR/bootstrap/20_extensions.sql" >/dev/null 2>&1 \
  && ok "83: bootstrap reexecutado recria o btree_gist" || bad "83: reexecucao do bootstrap falhou"

# --- 82: V015-V021 sobre o que já existe ------------------------------------------------------------------------------------
apply_range 15 "$MIGRATIONS_EXPECTED" && ok "82: V015-V0$MIGRATIONS_EXPECTED aplicam apos o bootstrap reexecutado" || bad "82: V015-V0$MIGRATIONS_EXPECTED falharam: $(head -2 "$work_err")"
n_tbl=$(admin_s psql -X -At -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                                 WHERE n.nspname = 'core' AND c.relkind = 'r'
                                   AND c.relname IN ('appointments', 'business_hours', 'holidays', 'clinic_holidays')")
[ "$n_tbl" = "4" ] && ok "82: as 4 tabelas da Fase 2 existem" || bad "82: tabelas da Fase 2 ausentes ($n_tbl de 4)"
n_hol=$(admin_s psql -X -At -c "SELECT count(*) FROM core.holidays" 2>/dev/null)
[ "$n_hol" = "50" ] && ok "82: seed dos feriados nacionais 2026-2030 (50 linhas)" || bad "82: seed de feriados com $n_hol linhas"
[ "$(admin_s psql -X -At -c "SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'")" = "1" ] \
  && ok "83: btree_gist presente apos a reaplicacao" || bad "83: btree_gist ausente"
# a V018 é idempotente: reaplicar não duplica nem falha
owner_s psql -X -q -v ON_ERROR_STOP=1 --single-transaction -f "$DB_DIR/migrations/V018__core_holidays_seed_2026_2030.sql" >/dev/null 2>&1 \
  && [ "$(admin_s psql -X -At -c "SELECT count(*) FROM core.holidays")" = "50" ] \
  && ok "82: reaplicar o seed da V018 e idempotente" || bad "82: o seed da V018 nao e idempotente"

[ $fail -eq 0 ] && echo "migrations_phase2: OK" || { echo "migrations_phase2: FALHOU"; exit 1; }
