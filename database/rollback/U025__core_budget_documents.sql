-- =====================================================================
-- U025__core_budget_documents.sql | reversão MANUAL de V025 (nunca automática; APAGA OS METADADOS DOS PDFs)
-- Executar : como crm_owner, por decisão humana, ANTES de U024 e DEPOIS de U028/U029. Os objetos no Storage ficam órfãos.
--            Depois de haver dados, restaure do pg_dump em vez de reverter.
-- =====================================================================
BEGIN;
DROP TABLE IF EXISTS core.budget_documents;  -- leva os triggers, índices e políticas junto
DROP FUNCTION IF EXISTS core.guard_budget_document_update();
DROP FUNCTION IF EXISTS core.budget_documents_before_insert();
COMMIT;
