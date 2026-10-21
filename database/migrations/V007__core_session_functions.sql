-- =====================================================================
-- V007__core_session_functions.sql | Fase 1 / F01-session-foundation
-- Objetivo : funções que leem a sessão do backend (papel e usuário da clínica) e o parâmetro único
--            de retenção clínica; EXECUTE da função de tenant ao crm_definer, para as políticas
--            escopadas ao tenant das features seguintes (F05, F06).
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U007__core_session_functions.sql
-- Depende  : V002 (core.current_clinic_id), V001 (USAGE de crm_definer em core).
-- Contrato : o backend define, na MESMA transação, app.clinic_id, app.role e app.user_id
--            (set_config(..., true)). Ausente ou inválido => NULL => as políticas negam (falha fechada).
-- =====================================================================

-- Papel do usuário na clínica. Comparação exata: qualquer outro valor (inclusive caixa diferente) é NULL.
CREATE FUNCTION core.current_app_role() RETURNS varchar
  LANGUAGE sql STABLE PARALLEL SAFE
  SET search_path = pg_catalog
AS $$
  SELECT CASE WHEN current_setting('app.role', true) IN ('DENTIST_ADMIN', 'RECEPTIONIST')
              THEN current_setting('app.role', true)
         END::varchar
$$;

-- Usuário autenticado. Vazio ou ausente devolve NULL; valor que não seja UUID gera erro de cast (22P02)
-- e aborta a transação, como em core.current_clinic_id().
CREATE FUNCTION core.current_user_id() RETURNS uuid
  LANGUAGE sql STABLE PARALLEL SAFE
  SET search_path = pg_catalog
AS $$ SELECT NULLIF(current_setting('app.user_id', true), '')::uuid $$;

-- Prazo de guarda do prontuário (LGPD + Lei 13.787/2018, a confirmar com assessoria jurídica antes de
-- dados reais). Único lugar do parâmetro: mudar o prazo exige nova migration.
CREATE FUNCTION core.clinical_retention_period() RETURNS interval
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
  SET search_path = pg_catalog
AS $$ SELECT interval '20 years' $$;

REVOKE EXECUTE ON FUNCTION core.current_app_role()          FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.current_user_id()           FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.clinical_retention_period() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION core.current_app_role()          TO crm_app, crm_definer;
GRANT EXECUTE ON FUNCTION core.current_user_id()           TO crm_app, crm_definer;
GRANT EXECUTE ON FUNCTION core.clinical_retention_period() TO crm_app, crm_definer;

-- As políticas do crm_definer (F05, F06) chamam a função de tenant: precisa poder executá-la.
GRANT EXECUTE ON FUNCTION core.current_clinic_id() TO crm_definer;
