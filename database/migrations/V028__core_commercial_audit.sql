-- =====================================================================
-- V028__core_commercial_audit.sql | Fase 3 / F06-commercial-audit
-- Objetivo : estende a trilha de auditoria (audit_log) a budgets, installments, payments e budget_documents: quem e quando
--            criou ou alterou cada um, SEM valores (nem valor monetário, status ou storage_key). procedure_types e budget_items
--            não são auditadas (configuração e dado derivado do cabeçalho).
-- Executar : scripts/apply_migrations.sh, como crm_owner (membro de crm_definer, dono de core.write_audit()).
-- Reverter : database/rollback/U028__core_commercial_audit.sql
-- Depende  : V012 (audit_log e write_audit), V020 (padrão da concessão temporária), V024 a V027.
-- Erros    : 28000 gravação sem app.user_id (a operação inteira reverte); 23514 table_name fora da lista.
-- Observações:
--   * write_audit() usa TG_TABLE_NAME e id/clinic_id da linha: as quatro tabelas têm as duas colunas; a função não muda.
--   * DROP/ADD CONSTRAINT valida as linhas existentes, mas não executa os triggers de linha de audit_log (o log continua só de inserção).
--   * Efeitos em cascata geram linhas próprias: cada mudança de item gera um UPDATE em budgets (total recalculado) e cada pagamento que
--     quita (ou estorno que reabre) gera um UPDATE em installments.
--   * CREATE TRIGGER exige EXECUTE do criador na função (dono crm_definer, EXECUTE revogado de PUBLIC); crm_owner é membro de
--     crm_definer SEM INHERIT: a concessão temporária é feita COMO crm_definer (SET LOCAL ROLE) e revogada ao fim (padrão da V020).
-- =====================================================================

ALTER TABLE core.audit_log DROP CONSTRAINT ck_audit_table;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_table CHECK (
  table_name IN ('patients', 'medical_histories', 'clinical_notes', 'patient_files', 'appointments',
                 'budgets', 'installments', 'payments', 'budget_documents'));

-- concessão temporária (como dono da função)
SET LOCAL ROLE crm_definer;
GRANT EXECUTE ON FUNCTION core.write_audit() TO crm_owner;
RESET ROLE;

CREATE TRIGGER trg_budgets_audit
  AFTER INSERT OR UPDATE OR DELETE ON core.budgets
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();
CREATE TRIGGER trg_inst_audit
  AFTER INSERT OR UPDATE OR DELETE ON core.installments
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();
CREATE TRIGGER trg_pay_audit
  AFTER INSERT OR UPDATE OR DELETE ON core.payments
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();
CREATE TRIGGER trg_bdoc_audit
  AFTER INSERT OR UPDATE OR DELETE ON core.budget_documents
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();

-- fechamento da concessão temporária
SET LOCAL ROLE crm_definer;
REVOKE EXECUTE ON FUNCTION core.write_audit() FROM crm_owner;
RESET ROLE;
