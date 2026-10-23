-- =====================================================================
-- V020__core_appointments_audit.sql | Fase 2 / F06-appointments-audit
-- Objetivo : estende a trilha de auditoria (audit_log) aos agendamentos: quem e quando criou ou alterou cada um,
--            SEM valores (nem horário, status ou notes). business_hours, clinic_holidays e holidays não são auditadas
--            (configuração, sem dado pessoal).
-- Executar : scripts/apply_migrations.sh, como crm_owner (membro de crm_definer, dono de core.write_audit()).
-- Reverter : database/rollback/U020__core_appointments_audit.sql
-- Depende  : V012 (audit_log e write_audit), V015 (appointments).
-- Erros    : 28000 gravação de agendamento sem app.user_id (a operação inteira reverte); 23514 table_name fora da lista.
-- Observações:
--   * write_audit() usa TG_TABLE_NAME: o valor 'appointments' só precisa passar pelo novo CHECK; a função não muda.
--   * DROP/ADD CONSTRAINT valida as linhas existentes, mas não executa os triggers de linha de audit_log
--     (trg_audit_log_append_only): o log continua só de inserção.
--   * O trigger inclui DELETE como nas outras tabelas auditadas; crm_app não apaga agendamento.
--   * CREATE TRIGGER exige EXECUTE do criador (crm_owner) na função. Desde a V012 ela pertence ao crm_definer e o EXECUTE foi
--     revogado de PUBLIC. crm_owner é membro de crm_definer SEM INHERIT (só SET): não herda o privilégio nem a propriedade.
--     Por isso a concessão temporária é feita COMO crm_definer (SET LOCAL ROLE) e revogada ao fim, na mesma transação.
-- =====================================================================

ALTER TABLE core.audit_log DROP CONSTRAINT ck_audit_table;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_table CHECK (
  table_name IN ('patients', 'medical_histories', 'clinical_notes', 'patient_files', 'appointments'));

-- concessão temporária (como dono da função)
SET LOCAL ROLE crm_definer;
GRANT EXECUTE ON FUNCTION core.write_audit() TO crm_owner;
RESET ROLE;

CREATE TRIGGER trg_appt_audit
  AFTER INSERT OR UPDATE OR DELETE ON core.appointments
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();

-- fechamento da concessão temporária
SET LOCAL ROLE crm_definer;
REVOKE EXECUTE ON FUNCTION core.write_audit() FROM crm_owner;
RESET ROLE;
