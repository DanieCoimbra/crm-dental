-- =====================================================================
-- U010__core_clinical_notes.sql | reversão MANUAL de V010 (nunca automática; APAGA OS DADOS)
-- Executar : como crm_owner, por decisão humana, DEPOIS de reverter V012 e V013. Faça backup antes.
-- =====================================================================
DROP TABLE IF EXISTS core.clinical_notes;
