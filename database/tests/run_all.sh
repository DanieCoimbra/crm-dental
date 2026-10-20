#!/usr/bin/env bash
# Harness local de testes do banco (precursor do db-pr.yml da F05).
# Fluxo: stubs do Supabase -> testes do bootstrap -> `flyway migrate` como crm_owner -> flyway_checks.sh -> tests/*.sql
# Requer psql, openssl 3 e o `flyway` (imagem database/tests/Dockerfile), um PostgreSQL DESCARTÁVEL e as variáveis padrão do psql
# (PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE) com um administrador.
set -euo pipefail

DB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export DB_DIR
HARNESS_ENV_FILE="$(mktemp)"; export HARNESS_ENV_FILE
trap 'rm -f "$HARNESS_ENV_FILE"' EXIT

echo "== stubs dos papeis do Supabase"
psql -X -q -v ON_ERROR_STOP=1 -f "$DB_DIR/tests/support/supabase_stubs.sql"

echo "== bootstrap"
bash "$DB_DIR/tests/bootstrap_checks.sh"
# shellcheck disable=SC1090
source "$HARNESS_ENV_FILE"

echo "== migrations (Flyway: migrate como crm_owner, cada migration em uma transacao)"
export FLYWAY_URL="jdbc:postgresql://${PGHOST}:${PGPORT:-5432}/${PGDATABASE:-postgres}" FLYWAY_USER=crm_owner FLYWAY_PASSWORD="$CRM_OWNER_PW"
bash "$DB_DIR/scripts/flyway.sh" migrate
unset FLYWAY_URL FLYWAY_USER FLYWAY_PASSWORD

echo "== Flyway: historico, validate e testes negativos (testes 23 e 24)"
bash "$DB_DIR/tests/flyway_checks.sh"

echo "== testes SQL"
for t in "$DB_DIR"/tests/*.sql; do
  echo "   $(basename "$t")"
  psql -X -q -v ON_ERROR_STOP=1 -f "$t"
done

echo "== backup e restauracao (testes 26 a 28)"
bash "$DB_DIR/tests/backup_restore.sh"

echo "== cadeia de rollback e reaplicacao (destrutivo; ultima etapa)"
CRM_OWNER_PW="$CRM_OWNER_PW" bash "$DB_DIR/tests/rollback_chain.sh"

echo "TODOS OS TESTES PASSARAM"
