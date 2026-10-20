#!/usr/bin/env bash
# Teste da cadeia de rollback: executa os scripts de reversão (manuais) como crm_owner, confirma
# que core ficou sem objetos, e prova que as migrations podem ser reaplicadas (ida e volta).
# DESTRUTIVO: só em banco descartável. Deve ser a ÚLTIMA etapa do harness.
# Requer: PGHOST/PGPORT/PGUSER/PGPASSWORD (administrador), CRM_OWNER_PW e DB_DIR.
set -uo pipefail

DB_DIR="${DB_DIR:?DB_DIR nao definido}"
: "${CRM_OWNER_PW:?CRM_OWNER_PW nao definido}"
ADMIN_USER="$PGUSER"; ADMIN_PW="$PGPASSWORD"
owner() { PGUSER=crm_owner PGPASSWORD="$CRM_OWNER_PW" "$@"; }
admin() { PGUSER="$ADMIN_USER" PGPASSWORD="$ADMIN_PW" "$@"; }

fail=0
ok()  { echo "  ok   - $1"; }
bad() { echo "  FAIL - $1"; fail=1; }

echo "rollback_chain:"

# Reversão em ordem inversa de dependência
for f in U006__core_bootstrap_functions U003_U004_U005__core_tables U002__core_tenant_function U001__core_privileges; do
  if owner psql -X -q -v ON_ERROR_STOP=1 -f "$DB_DIR/rollback/$f.sql" >/dev/null 2>&1; then
    ok "rollback $f"
  else
    bad "rollback $f falhou"; owner psql -X -q -v ON_ERROR_STOP=1 -f "$DB_DIR/rollback/$f.sql" 2>&1 | head -3
  fi
done

# core sem tabelas, funções, políticas nem ACL para os papéis do CRM
# O historico do Flyway (core.flyway_schema_history) fica de fora da contagem: ele nao e objeto de negocio.
n_obj=$(admin psql -X -At -c "SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='core' AND c.relkind IN ('r','v','m','S','p') AND c.relname <> 'flyway_schema_history')
                                    + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='core')
                                    + (SELECT count(*) FROM pg_policy)")
[ "$n_obj" = "0" ] && ok "schema core sem tabelas, funcoes ou politicas" || bad "restaram $n_obj objetos em core"
usage=$(admin psql -X -At -c "SELECT bool_or(has_schema_privilege(r,'core','USAGE')) FROM unnest(ARRAY['crm_app','crm_backup','crm_definer']) r")
[ "$usage" = "f" ] && ok "USAGE de core removido dos papeis do CRM" || bad "algum papel ainda tem USAGE em core ($usage)"
exec_pub=$(admin psql -X -At -c "SELECT count(*) FROM pg_default_acl d WHERE d.defaclobjtype='f' AND NOT EXISTS (SELECT 1 FROM aclexplode(d.defaclacl) a WHERE a.grantee = 0) AND d.defaclrole = (SELECT oid FROM pg_roles WHERE rolname='crm_owner')")
[ "$exec_pub" = "0" ] && ok "padrao de EXECUTE para PUBLIC restaurado" || bad "padrao de EXECUTE para PUBLIC nao foi restaurado"

# Reaplicação pelo Flyway (ida e volta). O rollback é manual, então o histórico também é corrigido à mão: o
# procedimento documentado em database/rollback/README.md apaga do histórico as versões revertidas.
owner psql -X -q -v ON_ERROR_STOP=1 -c "DELETE FROM core.flyway_schema_history WHERE version IS NOT NULL" >/dev/null 2>&1   && ok "historico do Flyway ajustado apos o rollback manual" || bad "nao foi possivel ajustar o historico do Flyway"
if FLYWAY_URL="jdbc:postgresql://${PGHOST}:${PGPORT:-5432}/${PGDATABASE:-postgres}" FLYWAY_USER=crm_owner FLYWAY_PASSWORD="$CRM_OWNER_PW"      bash "$DB_DIR/scripts/flyway.sh" migrate >/dev/null 2>&1; then
  ok "flyway migrate reaplicou todas as migrations apos o rollback"
else
  bad "flyway migrate falhou apos o rollback"
fi
for t in "$DB_DIR"/tests/*.sql; do
  if admin psql -X -q -v ON_ERROR_STOP=1 -f "$t" >/dev/null 2>&1; then
    ok "apos reaplicar: $(basename "$t")"
  else
    bad "apos reaplicar: $(basename "$t") falhou"; admin psql -X -q -v ON_ERROR_STOP=1 -f "$t" 2>&1 | grep -m1 ERROR
  fi
done

[ $fail -eq 0 ] && echo "rollback_chain: OK" || { echo "rollback_chain: FALHOU"; exit 1; }
