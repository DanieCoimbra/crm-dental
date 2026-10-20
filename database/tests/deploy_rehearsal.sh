#!/usr/bin/env bash
# Ensaio local dos workflows db-deploy e db-maintenance contra um PostgreSQL descartável (rede do compose).
# Usa o ramo "contêiner" do database/scripts/flyway.sh (o mesmo que o runner do GitHub usa): base VAZIA -> info, validate
# pré-deploy, migrate, info; segundo deploy sem nada a fazer; e, como crm_app, SELECT 1 + limpeza de tokens + histórico negado.
# Requer docker (rodar no host: bash database/tests/deploy_rehearsal.sh). Não faz parte do harness do PR.
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"   # raiz do repositório

export POSTGRES_PASSWORD="adm$(head -c 18 /dev/urandom | base64 | tr -d '/+=\n')"
OWNER_PW="own$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n')"
APP_PW="app$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n')"
BACKUP_PW="bkp$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n')"
C="docker compose -f database/docker-compose.yml"

$C down -v >/dev/null 2>&1 || true
$C up -d db >/dev/null 2>&1
for i in $(seq 1 30); do $C exec -T db pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done

echo "== bootstrap (como o db-bootstrap.yml)"
$C --profile test run --rm --no-deps --entrypoint bash -e OWNER_PW="$OWNER_PW" -e APP_PW="$APP_PW" -e BACKUP_PW="$BACKUP_PW" \
  -e PGHOST=db -e PGUSER=postgres -e PGPASSWORD="$POSTGRES_PASSWORD" tests -c '
  scram() { SCRAM_PASSWORD="$1" bash /database/scripts/scram_verifier.sh; }
  psql -X -q -v ON_ERROR_STOP=1 -f /database/tests/support/supabase_stubs.sql
  psql -X -q -v ON_ERROR_STOP=1 -v crm_owner_scram="$(scram "$OWNER_PW")" -v crm_app_scram="$(scram "$APP_PW")" \
       -v crm_backup_scram="$(scram "$BACKUP_PW")" -f /database/bootstrap/00_roles_and_schema.sql' 2>&1 | grep -v "^ Container" || true

echo "== conversor de URI como no workflow"
OWNER_URL="postgresql://crm_owner@db:5432/postgres?sslmode=disable"
if bash database/scripts/pg_uri_to_flyway_env.sh "$OWNER_URL" >/dev/null 2>&1; then echo "ERRO: aceitou sslmode=disable"; exit 1; fi
echo "   recusou sslmode=disable (TLS obrigatório) - ok"
# o Postgres local não tem TLS: para o ensaio usamos o JDBC direto, igual ao que o conversor produziria
export FLYWAY_URL="jdbc:postgresql://db:5432/postgres" FLYWAY_USER=crm_owner FLYWAY_PASSWORD="$OWNER_PW"
export FLYWAY_DOCKER_NETWORK=database_default

echo "== db-deploy: info -> validate -> migrate -> info (ramo contêiner do flyway.sh)"
bash database/scripts/flyway.sh info 2>&1 | grep -E "Pending|Empty|Versioned|Schema version" | head -8
bash database/scripts/flyway.sh validate '-ignoreMigrationPatterns=*:pending,*:future' 2>&1 | grep -E "Successfully|ERROR" | head -3
bash database/scripts/flyway.sh migrate 2>&1 | grep -E "Successfully applied|ERROR|Migrating" | head -10
bash database/scripts/flyway.sh info 2>&1 | grep -E "Success|Current version|ERROR" | head -8
echo "== segundo deploy (nada a fazer)"
bash database/scripts/flyway.sh migrate 2>&1 | grep -E "up to date|Successfully|ERROR" | head -3

echo "== db-maintenance: crm_app executa SELECT 1 e a limpeza"
ADM="$C exec -T -e PGPASSWORD=$POSTGRES_PASSWORD db psql -X -At -U postgres -d postgres -h localhost"
CLINIC=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || python -c "import uuid;print(uuid.uuid4())")
USER_ID=$(python -c "import uuid;print(uuid.uuid4())")
$ADM -c "INSERT INTO core.clinics(id,name) VALUES ('$CLINIC','Clínica ensaio')" >/dev/null
$ADM -c "INSERT INTO core.users(id,clinic_id,name,email,password_hash,role) VALUES ('$USER_ID','$CLINIC','Ensaio','ensaio@teste.local','x','RECEPTIONIST')" >/dev/null
for i in 1 2 3; do
  H=$(printf '%064x' $((RANDOM * i + i)))
  $ADM -c "INSERT INTO core.refresh_tokens(id,clinic_id,user_id,family_id,token_hash,expires_at) VALUES (gen_random_uuid(),'$CLINIC','$USER_ID',gen_random_uuid(),'$H', now() - interval '40 days')" >/dev/null
done
APP="$C exec -T -e PGPASSWORD=$APP_PW db psql -X -At -U crm_app -d postgres -h localhost -v ON_ERROR_STOP=1"
$APP -c "SELECT 1 AS keep_alive;"
$APP -c "SELECT 'refresh tokens apagados: ' || core.purge_expired_refresh_tokens();"
$APP -c "SELECT 'refresh tokens apagados: ' || core.purge_expired_refresh_tokens();"
echo "== crm_app NAO le o historico do Flyway (mesmo papel da manutenção)"
out=$($APP -c "SELECT count(*) FROM core.flyway_schema_history" 2>&1 || true); echo "   psql: $out" | head -2; if echo "$out" | grep -qi "permission denied"; then echo "   negado - ok"; else echo "ERRO: crm_app leu o historico"; exit 1; fi

$C down -v >/dev/null 2>&1
echo "ENSAIO CONCLUIDO"
