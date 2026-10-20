-- =====================================================================
-- U006__core_bootstrap_functions.sql | reversão MANUAL de V006 (nunca automática)
-- Executar : como crm_owner (membro de crm_definer), por decisão humana, numa transação.
-- Efeito   : remove as funções de login/refresh/limpeza e as concessões e políticas do
--            crm_definer. Sem as funções, o backend não consegue autenticar.
-- =====================================================================
BEGIN;
DROP FUNCTION IF EXISTS core.purge_expired_refresh_tokens();
DROP FUNCTION IF EXISTS core.find_refresh_token(varchar);
DROP FUNCTION IF EXISTS core.find_user_for_login(varchar);

DROP POLICY IF EXISTS refresh_definer_del ON core.refresh_tokens;
DROP POLICY IF EXISTS refresh_definer_sel ON core.refresh_tokens;
DROP POLICY IF EXISTS users_definer_sel   ON core.users;

REVOKE SELECT, DELETE ON core.refresh_tokens FROM crm_definer;
REVOKE SELECT (id, clinic_id, email, password_hash, role, active) ON core.users FROM crm_definer;
COMMIT;
