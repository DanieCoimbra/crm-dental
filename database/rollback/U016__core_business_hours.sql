-- =====================================================================
-- U016__core_business_hours.sql | reversão MANUAL de V016 (nunca automática; APAGA o horário comercial configurado)
-- Executar : como crm_owner, por decisão humana. Faça backup antes.
-- =====================================================================
DROP TABLE IF EXISTS core.business_hours;
