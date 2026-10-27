-- =====================================================================
-- U033__core_clinical_documents_audit.sql | reversão MANUAL de V033 (nunca automática)
-- Executar : como crm_owner, por decisão humana, DEPOIS de U034 e ANTES de U032. Remove o trigger, as funções, o grant e a política do
--            crm_definer e restaura os CHECKs anteriores de audit_log (table_name e action).
-- ATENÇÃO  : o log é só de inserção, então NÃO dá para apagar as linhas já gravadas de clinical_documents (INSERT, VOID, ANONYMIZE,
--            DOWNLOAD). Se existirem, o ADD CONSTRAINT falha e a transação inteira volta atrás (nada é removido). Nesse caso,
--            restaure do pg_dump.
-- =====================================================================
BEGIN;
DROP TRIGGER IF EXISTS trg_cdoc_audit ON core.clinical_documents;
DROP FUNCTION IF EXISTS core.record_document_download(uuid);
DROP FUNCTION IF EXISTS core.write_document_audit();
DROP POLICY IF EXISTS cdoc_definer_sel ON core.clinical_documents;
REVOKE SELECT (id, clinic_id, anonymized_at) ON core.clinical_documents FROM crm_definer;
ALTER TABLE core.audit_log DROP CONSTRAINT IF EXISTS ck_audit_action;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_action CHECK (
  action IN ('INSERT', 'UPDATE', 'DELETE', 'ANONYMIZE'));
ALTER TABLE core.audit_log DROP CONSTRAINT IF EXISTS ck_audit_table;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_table CHECK (
  table_name IN ('patients', 'medical_histories', 'clinical_notes', 'patient_files', 'appointments',
                 'budgets', 'installments', 'payments', 'budget_documents'));
COMMIT;
