-- =====================================================================
-- U020__core_appointments_audit.sql | reversão MANUAL de V020 (nunca automática)
-- Executar : como crm_owner, por decisão humana, ANTES de U015. Remove o trigger de auditoria dos agendamentos e restaura o
--            CHECK anterior de audit_log.table_name.
-- ATENÇÃO  : o log é só de inserção, então NÃO dá para apagar as linhas 'appointments' já gravadas. Se existirem, o ADD
--            CONSTRAINT falha e a transação inteira volta atrás (o trigger permanece). Nesse caso, restaure do pg_dump.
-- =====================================================================
BEGIN;
DROP TRIGGER IF EXISTS trg_appt_audit ON core.appointments;
ALTER TABLE core.audit_log DROP CONSTRAINT IF EXISTS ck_audit_table;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_table CHECK (
  table_name IN ('patients', 'medical_histories', 'clinical_notes', 'patient_files'));
COMMIT;
