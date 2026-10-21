-- =====================================================================
-- U013__core_patient_anonymization.sql | reversão MANUAL de V013 (nunca automática)
-- Executar : como crm_owner (membro de crm_definer), por decisão humana, numa transação, ANTES de V012 e
--            V008–V011. Sem a função, não há como anonimizar paciente; dados já anonimizados NÃO voltam.
-- =====================================================================
BEGIN;
DROP FUNCTION IF EXISTS core.anonymize_patient(uuid);

DROP TRIGGER IF EXISTS trg_files_no_anonymized_parent     ON core.patient_files;
DROP TRIGGER IF EXISTS trg_notes_no_anonymized_parent     ON core.clinical_notes;
DROP TRIGGER IF EXISTS trg_histories_no_anonymized_parent ON core.medical_histories;
DROP FUNCTION IF EXISTS core.forbid_child_of_anonymized();

DROP TRIGGER IF EXISTS trg_patients_guard_anonymized ON core.patients;
DROP FUNCTION IF EXISTS core.guard_anonymized_patient();

DROP POLICY IF EXISTS notes_definer_del     ON core.clinical_notes;
DROP POLICY IF EXISTS notes_definer_sel     ON core.clinical_notes;
DROP POLICY IF EXISTS histories_definer_del ON core.medical_histories;
DROP POLICY IF EXISTS histories_definer_sel ON core.medical_histories;
DROP POLICY IF EXISTS files_definer_upd     ON core.patient_files;
DROP POLICY IF EXISTS files_definer_sel     ON core.patient_files;
DROP POLICY IF EXISTS patients_definer_upd  ON core.patients;
DROP POLICY IF EXISTS patients_definer_sel  ON core.patients;

REVOKE SELECT, DELETE ON core.clinical_notes    FROM crm_definer;
REVOKE SELECT, DELETE ON core.medical_histories FROM crm_definer;
REVOKE SELECT, UPDATE ON core.patient_files     FROM crm_definer;
REVOKE SELECT, UPDATE ON core.patients          FROM crm_definer;
COMMIT;
