#!/usr/bin/env bash
# Teste da cadeia de rollback (Fase 1 e Fase 2: U021 -> U001): executa os scripts de reversão (manuais) como crm_owner, confirma
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
for f in U029__core_commercial_anonymization U028__core_commercial_audit U027__core_payments U026__core_installments U025__core_budget_documents U024__core_budgets U023__core_appointments_procedure_fk U022__core_procedure_types U021__core_appointments_anonymization U020__core_appointments_audit U019__core_notes_appointment_link U018__core_holidays_seed_2026_2030 U017__core_holidays U016__core_business_hours U015__core_appointments U014__core_clinic_timezone U013__core_patient_anonymization U012__core_audit_log U011__core_patient_files U010__core_clinical_notes U009__core_medical_histories U008__core_patients U007__core_session_functions U006__core_bootstrap_functions U003_U004_U005__core_tables U002__core_tenant_function U001__core_privileges; do
  if owner psql -X -q -v ON_ERROR_STOP=1 -f "$DB_DIR/rollback/$f.sql" >/dev/null 2>&1; then
    ok "rollback $f"
  else
    bad "rollback $f falhou"; owner psql -X -q -v ON_ERROR_STOP=1 -f "$DB_DIR/rollback/$f.sql" 2>&1 | head -3
  fi
done

# core sem tabelas, funções, políticas nem ACL para os papéis do CRM
n_obj=$(admin psql -X -At -c "SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='core' AND c.relkind IN ('r','v','m','S','p'))
                                    + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='core')
                                    + (SELECT count(*) FROM pg_policy)")
[ "$n_obj" = "0" ] && ok "schema core sem tabelas, funcoes ou politicas" || bad "restaram $n_obj objetos em core"
usage=$(admin psql -X -At -c "SELECT bool_or(has_schema_privilege(r,'core','USAGE')) FROM unnest(ARRAY['crm_app','crm_backup','crm_definer']) r")
[ "$usage" = "f" ] && ok "USAGE de core removido dos papeis do CRM" || bad "algum papel ainda tem USAGE em core ($usage)"
exec_pub=$(admin psql -X -At -c "SELECT count(*) FROM pg_default_acl d WHERE d.defaclobjtype='f' AND NOT EXISTS (SELECT 1 FROM aclexplode(d.defaclacl) a WHERE a.grantee = 0) AND d.defaclrole = (SELECT oid FROM pg_roles WHERE rolname='crm_owner')")
[ "$exec_pub" = "0" ] && ok "padrao de EXECUTE para PUBLIC restaurado" || bad "padrao de EXECUTE para PUBLIC nao foi restaurado"

# Reaplicação das migrations (ida e volta).
if PGUSER=crm_owner PGPASSWORD="$CRM_OWNER_PW" bash "$DB_DIR/scripts/apply_migrations.sh" >/dev/null 2>&1; then
  ok "apply_migrations.sh reaplicou todas as migrations apos o rollback"
else
  bad "apply_migrations.sh falhou apos o rollback"
fi
for t in "$DB_DIR"/tests/*.sql; do
  if admin psql -X -q -v ON_ERROR_STOP=1 -f "$t" >/dev/null 2>&1; then
    ok "apos reaplicar: $(basename "$t")"
  else
    bad "apos reaplicar: $(basename "$t") falhou"; admin psql -X -q -v ON_ERROR_STOP=1 -f "$t" 2>&1 | grep -m1 ERROR
  fi
done

[ $fail -eq 0 ] && echo "rollback_chain: OK" || { echo "rollback_chain: FALHOU"; exit 1; }
