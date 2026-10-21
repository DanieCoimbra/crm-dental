-- =====================================================================
-- U008__core_patients.sql | reversão MANUAL de V008 (nunca automática; APAGA OS DADOS de patients)
-- Executar : como crm_owner, por decisão humana, DEPOIS de reverter V009–V013 (as filhas e os
--            triggers de auditoria dependem de patients). Faça backup antes (scripts/backup.sh).
-- =====================================================================
DROP TABLE IF EXISTS core.patients;
