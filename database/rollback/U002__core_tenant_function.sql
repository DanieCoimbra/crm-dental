-- =====================================================================
-- U002__core_tenant_function.sql | reversão MANUAL de V002 (nunca automática)
-- Executar : como crm_owner, por decisão humana, ANTES de remover tabelas que usem as funções
--            (triggers e políticas que dependem delas impedem o DROP; remova-os primeiro).
-- =====================================================================
DROP FUNCTION IF EXISTS core.forbid_immutable_columns();
DROP FUNCTION IF EXISTS core.set_updated_at();
DROP FUNCTION IF EXISTS core.current_clinic_id();
