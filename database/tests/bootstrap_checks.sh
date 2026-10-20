#!/usr/bin/env bash
# F01-roles-and-bootstrap: testes do próprio script de bootstrap (idempotência, validação
# de variáveis, abortos da verificação, ausência de senha na saída e login SCRAM funcional).
# Requer: psql e openssl 3 no PATH; PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE apontando para
# um banco DESCARTÁVEL como administrador. Opcional: DB_DIR (padrão: pasta pai deste arquivo).
set -uo pipefail

DB_DIR="${DB_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BOOT="$DB_DIR/bootstrap/00_roles_and_schema.sql"
VERIFY="$DB_DIR/bootstrap/10_verify_roles.sql"
SCRAM="$DB_DIR/scripts/scram_verifier.sh"

gen() { head -c 36 /dev/urandom | base64 | tr -d '/+=\n' | head -c 32; }
OWNER_PW="${CRM_OWNER_PW:-$(gen)}"; APP_PW="${CRM_APP_PW:-$(gen)}"; BACKUP_PW="${CRM_BACKUP_PW:-$(gen)}"
scram() { SCRAM_PASSWORD="$1" bash "$SCRAM"; }
OWNER_SCRAM=$(scram "$OWNER_PW"); APP_SCRAM=$(scram "$APP_PW"); BACKUP_SCRAM=$(scram "$BACKUP_PW")

fail=0
ok()  { echo "  ok   - $1"; }
bad() { echo "  FAIL - $1"; fail=1; }

run_boot() {
  psql -X -q -v ON_ERROR_STOP=1 \
    -v crm_owner_scram="$OWNER_SCRAM" -v crm_app_scram="$APP_SCRAM" -v crm_backup_scram="$BACKUP_SCRAM" \
    -f "$BOOT" 2>&1
}
snapshot() {
  psql -X -At -c "SELECT rolname, rolcanlogin, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolinherit
                    FROM pg_roles WHERE rolname LIKE 'crm\_%' ORDER BY 1" \
       -c "SELECT n.nspname, o.rolname FROM pg_namespace n JOIN pg_roles o ON o.oid = n.nspowner WHERE n.nspname = 'core'" \
       -c "SELECT g.rolname, m.rolname FROM pg_auth_members am JOIN pg_roles g ON g.oid = am.roleid
             JOIN pg_roles m ON m.oid = am.member WHERE g.rolname LIKE 'crm\_%' OR m.rolname LIKE 'crm\_%' ORDER BY 1, 2"
}

echo "bootstrap_checks:"

# 0. gerador de verificador
case "$OWNER_SCRAM" in SCRAM-SHA-256\$4096:*) ok "gerador produz verificador SCRAM-SHA-256";; *) bad "formato do verificador: $OWNER_SCRAM";; esac
SCRAM_PASSWORD="curta" bash "$SCRAM" >/dev/null 2>&1 && bad "gerador deveria recusar senha curta" || ok "gerador recusa senha com menos de 24 caracteres"

# 1. primeira execução
out1=$(run_boot); rc=$?
[ $rc -eq 0 ] && ok "primeira execucao" || bad "primeira execucao: $out1"

# 2. idempotência: segunda execução sem erro e sem mudar o estado
snap1=$(snapshot)
out2=$(run_boot); rc=$?
snap2=$(snapshot)
[ $rc -eq 0 ] && ok "segunda execucao sem erro" || bad "segunda execucao: $out2"
[ "$snap1" = "$snap2" ] && ok "estado identico apos reexecutar" || bad "estado mudou apos reexecutar"

# 3. nenhuma senha na saída do psql; o texto claro nem chega ao servidor
leak=0
for pw in "$OWNER_PW" "$APP_PW" "$BACKUP_PW"; do
  case "$out1$out2" in *"$pw"*) leak=1;; esac
done
[ $leak -eq 0 ] && ok "senhas ausentes da saida do psql" || bad "senha apareceu na saida"

# 4. os verificadores funcionam: login SCRAM com a senha em texto claro, por TCP
if [ -n "${PGHOST:-}" ]; then
  for pair in "crm_owner:$OWNER_PW" "crm_app:$APP_PW" "crm_backup:$BACKUP_PW"; do
    role="${pair%%:*}"; pw="${pair#*:}"
    PGUSER="$role" PGPASSWORD="$pw" psql -X -At -c "SELECT current_user" 2>/dev/null | grep -qx "$role" \
      && ok "login SCRAM de $role com a senha original" || bad "login SCRAM de $role falhou"
    PGUSER="$role" PGPASSWORD="senha-errada-0123456789abcdef" psql -X -At -c "SELECT 1" >/dev/null 2>&1 \
      && bad "login de $role aceitou senha errada" || true
  done
fi

# 5. variável ausente
psql -X -q -v ON_ERROR_STOP=1 -v crm_owner_scram="$OWNER_SCRAM" -v crm_app_scram="$APP_SCRAM" -f "$BOOT" >/dev/null 2>&1 \
  && bad "deveria falhar sem crm_backup_scram" || ok "falha sem variavel obrigatoria"

# 6. senha em texto claro no lugar do verificador é recusada
psql -X -q -v ON_ERROR_STOP=1 -v crm_owner_scram="$OWNER_PW" -v crm_app_scram="$APP_SCRAM" -v crm_backup_scram="$BACKUP_SCRAM" -f "$BOOT" >/dev/null 2>&1 \
  && bad "deveria recusar senha em texto claro" || ok "recusa valor que nao e verificador SCRAM"

# 7. a verificação aborta com BYPASSRLS
psql -X -q -c "ALTER ROLE crm_app BYPASSRLS" >/dev/null
psql -X -q -v ON_ERROR_STOP=1 -f "$VERIFY" >/dev/null 2>&1 \
  && bad "verificacao deveria abortar com BYPASSRLS" || ok "verificacao aborta com BYPASSRLS"
out=$(run_boot); rc=$?
[ $rc -ne 0 ] && ok "bootstrap aborta com papel BYPASSRLS" || bad "bootstrap nao abortou com BYPASSRLS"
psql -X -q -c "ALTER ROLE crm_app NOBYPASSRLS" >/dev/null

# 8. a verificação aborta com membership privilegiada
psql -X -q -c "GRANT pg_read_all_data TO crm_app" >/dev/null 2>&1 || psql -X -q -c "GRANT pg_monitor TO crm_app" >/dev/null
psql -X -q -v ON_ERROR_STOP=1 -f "$VERIFY" >/dev/null 2>&1 \
  && bad "verificacao deveria abortar com membership" || ok "verificacao aborta com membership privilegiada"
psql -X -q -c "REVOKE pg_read_all_data FROM crm_app" >/dev/null 2>&1
psql -X -q -c "REVOKE pg_monitor FROM crm_app" >/dev/null 2>&1

# 9. a verificação aborta com dono do schema errado
psql -X -q -c "ALTER SCHEMA core OWNER TO CURRENT_USER" >/dev/null
psql -X -q -v ON_ERROR_STOP=1 -f "$VERIFY" >/dev/null 2>&1 \
  && bad "verificacao deveria abortar com dono errado" || ok "verificacao aborta com dono do schema errado"
psql -X -q -c "ALTER SCHEMA core OWNER TO crm_owner" >/dev/null

# 10. estado final saudável
psql -X -q -v ON_ERROR_STOP=1 -f "$VERIFY" >/dev/null 2>&1 && ok "verificacao passa no estado final" || bad "verificacao falhou no estado final"

# Exporta as senhas geradas para o restante do harness (logins de crm_owner nas migrations)
printf 'export CRM_OWNER_PW=%q CRM_APP_PW=%q CRM_BACKUP_PW=%q\n' "$OWNER_PW" "$APP_PW" "$BACKUP_PW" > "${HARNESS_ENV_FILE:-/dev/null}"

[ $fail -eq 0 ] && echo "bootstrap_checks: OK" || { echo "bootstrap_checks: FALHOU"; exit 1; }
