#!/usr/bin/env bash
# Harness local de testes do banco (precursor do db-pr.yml da F05).
# Fase 2: MIGRATIONS_EXPECTED confere a quantidade de migrations (migrations_phase2.sh).
# Fluxo: stubs do Supabase -> testes do bootstrap -> migrations como crm_owner (apply_migrations.sh) -> tests/*.sql
# Requer psql, openssl 3 e age (imagem database/tests/Dockerfile), um PostgreSQL DESCARTÁVEL e as variáveis padrão do psql
# (PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE) com um administrador.
set -euo pipefail

DB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export DB_DIR
MIGRATIONS_EXPECTED=21; export MIGRATIONS_EXPECTED
HARNESS_ENV_FILE="$(mktemp)"; export HARNESS_ENV_FILE
trap 'rm -f "$HARNESS_ENV_FILE"' EXIT

echo "== stubs dos papeis do Supabase"
psql -X -q -v ON_ERROR_STOP=1 -f "$DB_DIR/tests/support/supabase_stubs.sql"

echo "== bootstrap"
bash "$DB_DIR/tests/bootstrap_checks.sh"
# shellcheck disable=SC1090
source "$HARNESS_ENV_FILE"

echo "== migrations (como crm_owner, cada uma em uma transacao)"
PGUSER=crm_owner PGPASSWORD="$CRM_OWNER_PW" bash "$DB_DIR/scripts/apply_migrations.sh"

echo "== testes SQL"
for t in "$DB_DIR"/tests/*.sql; do
  echo "   $(basename "$t")"
  psql -X -q -v ON_ERROR_STOP=1 -f "$t"
done

echo "== concorrencia do conflito de horario (teste 63; duas conexoes como crm_app)"
CRM_APP_PW="$CRM_APP_PW" bash "$DB_DIR/tests/appointments_concurrency.sh"

echo "== migrations da Fase 2 e guarda do btree_gist (testes 82 e 83; banco descartavel proprio)"
CRM_OWNER_PW="$CRM_OWNER_PW" CRM_APP_PW="$CRM_APP_PW" CRM_BACKUP_PW="$CRM_BACKUP_PW" bash "$DB_DIR/tests/migrations_phase2.sh"

echo "== backup e restauracao (testes 26 a 28 e 85)"
bash "$DB_DIR/tests/backup_restore.sh"

echo "== cadeia de rollback e reaplicacao (destrutivo; ultima etapa)"
CRM_OWNER_PW="$CRM_OWNER_PW" bash "$DB_DIR/tests/rollback_chain.sh"

echo "TODOS OS TESTES PASSARAM"
