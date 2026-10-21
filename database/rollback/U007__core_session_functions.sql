-- =====================================================================
-- U007__core_session_functions.sql | reversão MANUAL de V007 (nunca automática)
-- Executar : como crm_owner, por decisão humana, DEPOIS de reverter as migrations V008–V013
--            (políticas e triggers que usam estas funções impedem o DROP).
-- Observação: o DROP remove também os EXECUTE concedidos. O EXECUTE de crm_definer em
--            core.current_clinic_id() (concedido pela V007) é revogado aqui.
-- Extensão : bootstrap/20_extensions.sql não é revertido (ver database/rollback/README.md).
-- =====================================================================
REVOKE EXECUTE ON FUNCTION core.current_clinic_id() FROM crm_definer;
DROP FUNCTION IF EXISTS core.clinical_retention_period();
DROP FUNCTION IF EXISTS core.current_user_id();
DROP FUNCTION IF EXISTS core.current_app_role();
