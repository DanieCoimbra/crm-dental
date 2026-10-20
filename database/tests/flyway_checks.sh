#!/usr/bin/env bash
# F05-migration-pipeline: testes 23 e 24 com o Flyway REAL contra o banco do harness (já migrado como crm_owner).
#   23  as migrations aplicaram do zero (uma linha de histórico por arquivo, todas com sucesso) e o migrate é idempotente
#   24  `validate` passa; alterar uma migration aplicada o faz falhar (teste negativo); mais: nomes inválidos,
#       migration fora de ordem, `clean` desabilitado e migration que falha sem deixar rastro
# Requer: psql, o `flyway` local (imagem de testes) para os casos negativos que usam diretórios temporários,
#         PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE (administrador), CRM_OWNER_PW e DB_DIR.
set -uo pipefail

DB_DIR="${DB_DIR:?DB_DIR nao definido}"
: "${CRM_OWNER_PW:?CRM_OWNER_PW nao definido}"
HOST="${PGHOST:?PGHOST nao definido}"; PORT="${PGPORT:-5432}"; NAME="${PGDATABASE:-postgres}"

fail=0
ok()  { echo "  ok   - $1"; }
bad() { echo "  FAIL - $1"; fail=1; }

fw() {
  FLYWAY_URL="jdbc:postgresql://${HOST}:${PORT}/${NAME}" FLYWAY_USER=crm_owner FLYWAY_PASSWORD="$CRM_OWNER_PW" \
    bash "$DB_DIR/scripts/flyway.sh" "$@"
}
sql() { psql -X -At -v ON_ERROR_STOP=1 -c "$1"; }

echo "flyway_checks:"

if ! command -v flyway >/dev/null 2>&1; then
  echo "  FAIL - os casos negativos precisam do 'flyway' local (use a imagem database/tests/Dockerfile)"
  exit 1
fi

# --- Teste 23: aplicou do zero -------------------------------------------------------------------
files=$(ls "$DB_DIR"/migrations/V*.sql | wc -l)
applied=$(sql "SELECT count(*) FROM core.flyway_schema_history WHERE version IS NOT NULL AND success")
[ "$applied" = "$files" ] && ok "23: $files migrations aplicadas do zero (1 linha de historico por arquivo)" \
                          || bad "23: $files arquivos, $applied aplicadas"

first_run_rows=$(sql "SELECT count(*) FROM core.flyway_schema_history")
if out=$(fw migrate 2>&1); then
  second_run_rows=$(sql "SELECT count(*) FROM core.flyway_schema_history")
  [ "$first_run_rows" = "$second_run_rows" ] && ok "23: segundo migrate nao muda nada (idempotente)" \
                                              || bad "23: segundo migrate alterou o historico"
else
  bad "23: segundo migrate falhou: $(echo "$out" | tail -3)"
fi

# --- Teste 24: validate passa; checksum alterado falha ---------------------------------------------
if out=$(fw validate 2>&1); then ok "24: flyway validate passa no estado atual"; else bad "24: validate falhou: $(echo "$out" | tail -3)"; fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cp "$DB_DIR"/migrations/V*.sql "$tmp"/

edited="$tmp/edited"; mkdir "$edited"; cp "$tmp"/V*.sql "$edited"/
printf '\n-- alteracao indevida depois de aplicada\n' >> "$edited/V003__core_clinics.sql"
out=$(fw validate "-locations=filesystem:$edited" 2>&1); rc=$?
if [ $rc -ne 0 ] && echo "$out" | grep -qi "checksum"; then ok "24: migration editada depois de aplicada faz o validate falhar (checksum)"
else bad "24: validate NAO acusou a migration editada (rc=$rc): $(echo "$out" | tail -3)"; fi

# a edição só valeu no diretório temporário: os arquivos do repositório continuam válidos
fw validate >/dev/null 2>&1 && ok "24: arquivos do repositorio intactos apos o teste negativo" || bad "24: validate do repositorio falhou apos o negativo"

# --- Convenções do contrato ---------------------------------------------------------------------------
badname="$tmp/badname"; mkdir "$badname"; cp "$tmp"/V*.sql "$badname"/; printf 'SELECT 1;\n' > "$badname/V7_nome_invalido.sql"
out=$(fw validate "-locations=filesystem:$badname" 2>&1); rc=$?
if [ $rc -ne 0 ] && echo "$out" | grep -qi "naming\|name"; then ok "validateMigrationNaming: nome fora do padrao V<NNN>__descricao e recusado"
else bad "nome invalido nao foi recusado (rc=$rc): $(echo "$out" | tail -3)"; fi

ooo="$tmp/ooo"; mkdir "$ooo"; cp "$tmp"/V*.sql "$ooo"/; printf 'SELECT 1;\n' > "$ooo/V000__antes_de_tudo.sql"
out=$(fw validate "-locations=filesystem:$ooo" 2>&1); rc=$?
[ $rc -ne 0 ] && ok "outOfOrder=false: migration com versao anterior a ja aplicada e recusada" \
              || bad "migration fora de ordem foi aceita: $(echo "$out" | tail -3)"
out=$(fw migrate "-locations=filesystem:$ooo" 2>&1); rc=$?
[ $rc -ne 0 ] && ok "outOfOrder=false: migrate tambem nao aplica a migration fora de ordem" \
              || bad "migrate aplicou migration fora de ordem"
[ "$(sql "SELECT count(*) FROM core.flyway_schema_history WHERE version = '0'")" = "0" ] \
  && ok "nada foi gravado no historico pela tentativa fora de ordem" || bad "historico recebeu a versao 0"

out=$(fw clean 2>&1); rc=$?
if [ $rc -ne 0 ] && echo "$out" | grep -qi "clean"; then ok "cleanDisabled=true: flyway clean e recusado"
else bad "flyway clean nao foi recusado (rc=$rc)"; fi
[ "$(sql "SELECT count(*) FROM core.clinics")" != "" ] && ok "clean recusado: tabelas intactas" || bad "tabelas sumiram"

# --- validate ANTES do migrate (como o db-deploy.yml): pendentes passam, adulteracao continua falhando ---------
PRE='-ignoreMigrationPatterns=*:pending,*:future'
pending="$tmp/pending"; mkdir "$pending"; cp "$tmp"/V*.sql "$pending"/
pend_next=$(printf '%03d' $((files + 1)))
printf 'SELECT 1;\n' > "$pending/V${pend_next}__nova_migration_pendente.sql"
out=$(fw validate "-locations=filesystem:$pending" 2>&1); rc=$?
[ $rc -ne 0 ] && ok "validate estrito acusa migration pendente (por isso o deploy ignora so as pendentes)" \
              || bad "validate estrito deveria acusar a pendente"
out=$(fw validate "$PRE" "-locations=filesystem:$pending" 2>&1); rc=$?
[ $rc -eq 0 ] && ok "validate de pre-deploy aceita migration nova pendente" || bad "validate de pre-deploy recusou a pendente: $(echo "$out" | tail -3)"
tampered_pending="$tmp/tampered_pending"; mkdir "$tampered_pending"; cp "$pending"/V*.sql "$tampered_pending"/
printf '\n-- editada\n' >> "$tampered_pending/V004__core_users.sql"
out=$(fw validate "$PRE" "-locations=filesystem:$tampered_pending" 2>&1); rc=$?
if [ $rc -ne 0 ] && echo "$out" | grep -qi "checksum"; then ok "validate de pre-deploy continua acusando migration aplicada e editada"
else bad "validate de pre-deploy deixou passar migration editada (rc=$rc)"; fi

# --- Migration que falha em producao nao deixa rastro (transacao desfaz) -----------------------------------
failing="$tmp/failing"; mkdir "$failing"; cp "$tmp"/V*.sql "$failing"/
next=$(printf '%03d' $((files + 1)))
printf 'CREATE TABLE core.t_falha_f05 (id int);\nSELECT 1/0;\n' > "$failing/V${next}__migration_que_falha.sql"
rows_before=$(sql "SELECT count(*) FROM core.flyway_schema_history")
out=$(fw migrate "-locations=filesystem:$failing" 2>&1); rc=$?
[ $rc -ne 0 ] && ok "migration com erro faz o migrate falhar" || bad "migrate nao falhou com migration quebrada"
[ "$(sql "SELECT to_regclass('core.t_falha_f05') IS NULL")" = "t" ] && ok "a transacao desfez o CREATE TABLE da migration quebrada" \
  || bad "a tabela da migration quebrada sobrou"
[ "$(sql "SELECT count(*) FROM core.flyway_schema_history")" = "$rows_before" ] && ok "historico sem registro da migration quebrada" \
  || bad "historico mudou apos migration quebrada"
fw validate >/dev/null 2>&1 && ok "banco continua valido apos a falha" || bad "validate falhou apos a migration quebrada"

# --- Conversor de URI dos secrets (usado pelo db-deploy.yml) -------------------------------------------------
conv="$DB_DIR/scripts/pg_uri_to_flyway_env.sh"
out=$(bash "$conv" 'postgresql://crm_owner.abcd1234@aws-0-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=require' 2>&1); rc=$?
got=$( eval "$out"; printf '%s|%s' "${FLYWAY_URL:-}" "${FLYWAY_USER:-}" )
if [ $rc -eq 0 ] && [ "$got" = 'jdbc:postgresql://aws-0-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=require|crm_owner.abcd1234' ]; then
  ok "uri->jdbc: converte URI do Supabase (usuario com .ref, porta, sslmode), pronta para eval"
else bad "uri->jdbc: saida inesperada: $out"; fi
bash "$conv" 'postgresql://crm_owner:segredo@host:5432/postgres?sslmode=require' >/dev/null 2>&1 \
  && bad "uri->jdbc: aceitou URI com senha" || ok "uri->jdbc: recusa URI com senha embutida"
bash "$conv" 'postgresql://crm_owner@host:5432/postgres' >/dev/null 2>&1 \
  && bad "uri->jdbc: aceitou URI sem sslmode" || ok "uri->jdbc: recusa URI sem TLS"
bash "$conv" 'postgresql://crm_owner@host:5432/postgres?sslmode=disable' >/dev/null 2>&1 \
  && bad "uri->jdbc: aceitou sslmode=disable" || ok "uri->jdbc: recusa sslmode=disable"
bash "$conv" 'mysql://u@h/db?sslmode=require' >/dev/null 2>&1 \
  && bad "uri->jdbc: aceitou esquema diferente" || ok "uri->jdbc: recusa esquema que nao e postgresql"
out=$(bash "$conv" 'postgresql://u@h:6543/db?sslmode=verify-full&connect_timeout=10' 2>&1)
got=$( eval "$out"; printf '%s' "${FLYWAY_URL:-}" )
[ "$got" = 'jdbc:postgresql://h:6543/db?sslmode=verify-full&connect_timeout=10' ] \
  && ok "uri->jdbc: preserva outros parametros" || bad "uri->jdbc: perdeu parametros: $out"

# Papel exigido (o deploy só aceita crm_owner)
bash "$conv" 'postgresql://crm_owner.abcd1234@h:5432/db?sslmode=require' crm_owner >/dev/null 2>&1   && ok "uri->jdbc: aceita crm_owner.<ref> quando o papel exigido e crm_owner" || bad "uri->jdbc: recusou crm_owner.<ref>"
bash "$conv" 'postgresql://crm_owner@h:5432/db?sslmode=require' crm_owner >/dev/null 2>&1   && ok "uri->jdbc: aceita crm_owner puro" || bad "uri->jdbc: recusou crm_owner"
for wrong in postgres crm_app crm_owner_x crm_ownerx.abc supabase_admin; do
  bash "$conv" "postgresql://$wrong@h:5432/db?sslmode=require" crm_owner >/dev/null 2>&1     && bad "uri->jdbc: aceitou o usuario $wrong como dono" || true
done
bash "$conv" 'postgresql://postgres@h:5432/db?sslmode=require' crm_owner >/dev/null 2>&1   && bad "uri->jdbc: aceitou postgres como dono" || ok "uri->jdbc: recusa usuario que nao e o papel exigido (postgres, crm_app, crm_owner_x...)"

# Entrada maliciosa nunca vira comando no eval do workflow
rm -f /tmp/pwned_f05
for evil in "postgresql://u@h:5432/db?sslmode=require&x=';touch /tmp/pwned_f05;'" \
            'postgresql://u@h:5432/db?sslmode=require&x=$(touch /tmp/pwned_f05)' \
            'postgresql://u@h:5432/db?sslmode=require&x=`touch /tmp/pwned_f05`' \
            'postgresql://u@h:5432/db?sslmode=require;touch /tmp/pwned_f05'; do
  bash "$conv" "$evil" >/dev/null 2>&1 && bad "uri->jdbc: aceitou URI maliciosa: $evil"
done
[ ! -e /tmp/pwned_f05 ] && ok "uri->jdbc: URIs com aspas, cifrao, crase ou ponto e virgula sao recusadas (sem injecao)" \
                        || bad "uri->jdbc: executou codigo vindo da URI"

[ $fail -eq 0 ] && echo "flyway_checks: OK" || { echo "flyway_checks: FALHOU"; exit 1; }
