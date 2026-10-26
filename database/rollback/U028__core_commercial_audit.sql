-- =====================================================================
-- U028__core_commercial_audit.sql | reversão MANUAL de V028 (nunca automática)
-- Executar : como crm_owner, por decisão humana, ANTES de U027, U026, U025 e U024. Remove os triggers de auditoria e restaura o
--            CHECK anterior de audit_log.table_name.
-- ATENÇÃO  : o log é só de inserção, então NÃO dá para apagar as linhas já gravadas das tabelas novas. Se existirem, o ADD
--            CONSTRAINT falha e a transação inteira volta atrás (os triggers permanecem). Nesse caso, restaure do pg_dump.
-- =====================================================================
BEGIN;
DROP TRIGGER IF EXISTS trg_bdoc_audit    ON core.budget_documents;
DROP TRIGGER IF EXISTS trg_pay_audit     ON core.payments;
DROP TRIGGER IF EXISTS trg_inst_audit    ON core.installments;
DROP TRIGGER IF EXISTS trg_budgets_audit ON core.budgets;
ALTER TABLE core.audit_log DROP CONSTRAINT IF EXISTS ck_audit_table;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_table CHECK (
  table_name IN ('patients', 'medical_histories', 'clinical_notes', 'patient_files', 'appointments'));
COMMIT;
