#!/usr/bin/env bash
# Prepara o PostgreSQL LOCAL do docker-compose com os scripts da função banco:
#   1. bootstrap (papéis e schema core) como administrador, com verificadores SCRAM gerados aqui;
#   2. migrations V*.sql como crm_owner (cada uma em uma transação, como o Flyway).
# O bootstrap é idempotente; as migrations só rodam se o schema ainda estiver vazio.
# Para recomeçar do zero: docker compose down -v
# Requer, no ambiente: POSTGRES_PASSWORD, DB_OWNER_PASSWORD, DB_APP_PASSWORD, DB_BACKUP_PASSWORD.
set -euo pipefail

: "${POSTGRES_PASSWORD:?defina POSTGRES_PASSWORD}"
: "${DB_OWNER_PASSWORD:?defina DB_OWNER_PASSWORD}"
: "${DB_APP_PASSWORD:?defina DB_APP_PASSWORD}"
: "${DB_BACKUP_PASSWORD:?defina DB_BACKUP_PASSWORD}"

DB_DIR=/database
export PGHOST="${PGHOST:-db}" PGPORT="${PGPORT:-5432}" PGDATABASE=postgres

scram() { SCRAM_PASSWORD="$1" bash "$DB_DIR/scripts/scram_verifier.sh"; }
admin() { PGUSER=postgres PGPASSWORD="$POSTGRES_PASSWORD" "$@"; }
owner() { PGUSER=crm_owner PGPASSWORD="$DB_OWNER_PASSWORD" "$@"; }

echo "== bootstrap (papeis e schema core)"
admin psql -X -q -v ON_ERROR_STOP=1 \
  -v crm_owner_scram="$(scram "$DB_OWNER_PASSWORD")" \
  -v crm_app_scram="$(scram "$DB_APP_PASSWORD")" \
  -v crm_backup_scram="$(scram "$DB_BACKUP_PASSWORD")" \
  -f "$DB_DIR/bootstrap/00_roles_and_schema.sql"

if [ "$(admin psql -X -At -c "SELECT to_regclass('core.refresh_tokens') IS NOT NULL")" = "t" ]; then
  echo "== migrations ja aplicadas; nada a fazer (use 'docker compose down -v' para recomecar)"
  exit 0
fi

echo "== migrations"
for f in $(ls "$DB_DIR"/migrations/V*.sql | sort); do
  echo "   $(basename "$f")"
  owner psql -X -q -v ON_ERROR_STOP=1 --single-transaction -f "$f"
done
echo "== banco local pronto"
