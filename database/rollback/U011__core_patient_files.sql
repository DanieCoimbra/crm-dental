-- =====================================================================
-- U011__core_patient_files.sql | reversão MANUAL de V011 (nunca automática; APAGA OS METADADOS)
-- Executar : como crm_owner, por decisão humana, DEPOIS de reverter V012 e V013. Faça backup antes.
-- Observação: os objetos no Supabase Storage NÃO são apagados; ficam órfãos (limpeza manual no painel).
-- =====================================================================
DROP TABLE IF EXISTS core.patient_files;
