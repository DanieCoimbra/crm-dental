-- =====================================================================
-- U032__core_clinical_documents.sql | reversão MANUAL de V032 (nunca automática; APAGA OS DOCUMENTOS CLÍNICOS)
-- Executar : como crm_owner, por decisão humana, DEPOIS de U034 e U033 e ANTES de U031. Com documentos gravados, restaure do pg_dump
--            em vez de reverter (o dono não enxerga linhas sob FORCE RLS, então a reversão não consegue conferir se há documentos).
--            Se a auditoria já gravou linhas de clinical_documents, a U033 falha antes (o log é só de inserção).
-- =====================================================================
BEGIN;
DROP TABLE IF EXISTS core.clinical_documents;  -- leva os triggers, índices, políticas e grants junto
DROP FUNCTION IF EXISTS core.guard_clinical_document_update();
DROP FUNCTION IF EXISTS core.clinical_documents_before_insert();
DROP FUNCTION IF EXISTS core.clinical_documents_block();
COMMIT;
