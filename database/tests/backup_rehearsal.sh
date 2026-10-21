#!/usr/bin/env bash
# Ensaio local do par db-backup / db-restore-check, de ponta a ponta e SEPARADO como nos workflows:
#   1. banco de origem descartável: bootstrap + migrations + dados em duas clínicas
#   2. backup.sh como crm_backup (o que o db-backup.yml executa) -> arquivo .age numa pasta do host ("o artefato")
#   3. serviço `restore` do compose (o que o db-restore-check.yml executa) lê só a pasta e a chave privada
# Requer docker (rodar no host: bash database/tests/backup_rehearsal.sh). Não faz parte do harness do PR.
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

export POSTGRES_PASSWORD="adm$(head -c 18 /dev/urandom | base64 | tr -d '/+=\n')"
OWNER_PW="own$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n')"
APP_PW="app$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n')"
BACKUP_PW="bkp$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n')"
# pasta dentro do repositório: o Docker Desktop (Windows) não enxerga o /tmp do Git Bash
OUT="$PWD/database/.rehearsal-$$"
mkdir -p "$OUT"
C="docker compose -f database/docker-compose.yml"
trap '$C --profile test --profile restore down -v >/dev/null 2>&1 || true; rm -rf "$OUT"' EXIT

$C --profile test --profile restore down -v >/dev/null 2>&1 || true
$C up -d db >/dev/null 2>&1
for _ in $(seq 1 30); do $C exec -T db pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done

echo "== origem: bootstrap, migrations, dados e backup (db-backup.yml)"
MSYS_NO_PATHCONV=1 $C --profile test run --rm --no-deps --entrypoint bash -v "$OUT:/out" \
  -e OWNER_PW="$OWNER_PW" -e APP_PW="$APP_PW" -e BACKUP_PW="$BACKUP_PW" -e PGHOST=db -e PGUSER=postgres -e PGPASSWORD="$POSTGRES_PASSWORD" \
  tests -c '
  set -euo pipefail
  scram() { SCRAM_PASSWORD="$1" bash /database/scripts/scram_verifier.sh; }
  psql -X -q -v ON_ERROR_STOP=1 -f /database/tests/support/supabase_stubs.sql
  psql -X -q -v ON_ERROR_STOP=1 -v crm_owner_scram="$(scram "$OWNER_PW")" -v crm_app_scram="$(scram "$APP_PW")" \
       -v crm_backup_scram="$(scram "$BACKUP_PW")" -f /database/bootstrap/00_roles_and_schema.sql
  psql -X -q -v ON_ERROR_STOP=1 -f /database/bootstrap/20_extensions.sql
  PGUSER=crm_owner PGPASSWORD="$OWNER_PW" bash /database/scripts/apply_migrations.sh >/dev/null
  psql -X -q -v ON_ERROR_STOP=1 <<SQL
    INSERT INTO core.clinics (id, name) VALUES ('"'"'11111111-1111-4111-8111-111111111111'"'"', '"'"'Clinica Ensaio A'"'"'), ('"'"'22222222-2222-4222-8222-222222222222'"'"', '"'"'Clinica Ensaio B'"'"');
    INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES
      (gen_random_uuid(), '"'"'11111111-1111-4111-8111-111111111111'"'"', '"'"'Dentista A'"'"', '"'"'a@ensaio.test'"'"', '"'"'x'"'"', '"'"'DENTIST_ADMIN'"'"'),
      (gen_random_uuid(), '"'"'22222222-2222-4222-8222-222222222222'"'"', '"'"'Dentista B'"'"', '"'"'b@ensaio.test'"'"', '"'"'x'"'"', '"'"'DENTIST_ADMIN'"'"');
SQL
  age-keygen -o /out/key.txt >/dev/null 2>&1
  export BACKUP_URL="postgresql://crm_backup@db:5432/postgres" PGPASSWORD="$BACKUP_PW" BACKUP_REQUIRE_TLS=0 OUT_DIR=/out
  export BACKUP_AGE_PUBLIC_KEY="$(age-keygen -y /out/key.txt)"
  bash /database/scripts/backup.sh
  chmod 644 /out/*
'

KEY="$(grep '^AGE-SECRET-KEY-' "$OUT/key.txt")"
FILE="$(find "$OUT" -maxdepth 1 -name 'crm-core-*.dump.age' -printf '%f\n' | head -1)"
[ -n "$FILE" ] || { echo "ERRO: backup não foi gerado"; exit 1; }
mkdir "$OUT/artifact" && mv "$OUT/$FILE" "$OUT/artifact/" && rm -f "$OUT/key.txt"   # a pasta "artifact" só tem o .age

echo "== destino: serviço restore do compose, com a chave privada vinda do ambiente (db-restore-check.yml)"
BACKUP_DIR="$OUT/artifact" BACKUP_FILE_NAME="$FILE" BACKUP_AGE_PRIVATE_KEY="$KEY" \
  $C --profile restore run --rm --build restore
echo "ENSAIO CONCLUIDO"
