-- =====================================================================
-- U009__core_medical_histories.sql | reversão MANUAL de V009 (nunca automática; APAGA OS DADOS)
-- Executar : como crm_owner, por decisão humana, DEPOIS de reverter V012 e V013. Faça backup antes.
-- =====================================================================
DROP TABLE IF EXISTS core.medical_histories;
