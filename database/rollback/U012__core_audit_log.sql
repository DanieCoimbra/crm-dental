-- =====================================================================
-- U012__core_audit_log.sql | reversão MANUAL de V012 (nunca automática; APAGA A TRILHA DE AUDITORIA)
-- Executar : como crm_owner (membro de crm_definer), por decisão humana, numa transação, DEPOIS de
--            reverter V013 e ANTES de V008–V011 (os triggers estão nas tabelas clínicas).
-- =====================================================================
BEGIN;
DROP TRIGGER IF EXISTS trg_files_audit     ON core.patient_files;
DROP TRIGGER IF EXISTS trg_notes_audit     ON core.clinical_notes;
DROP TRIGGER IF EXISTS trg_histories_audit ON core.medical_histories;
DROP TRIGGER IF EXISTS trg_patients_audit  ON core.patients;
DROP FUNCTION IF EXISTS core.write_audit();
DROP TABLE IF EXISTS core.audit_log;
DROP FUNCTION IF EXISTS core.audit_log_append_only();
COMMIT;
