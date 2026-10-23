-- =====================================================================
-- U017__core_holidays.sql | reversão MANUAL de V017 (nunca automática; APAGA os feriados da clínica e os nacionais)
-- Executar : como crm_owner, por decisão humana, DEPOIS de U018 (o seed). Faça backup antes.
-- =====================================================================
BEGIN;
DROP TABLE IF EXISTS core.clinic_holidays;
DROP TABLE IF EXISTS core.holidays;          -- leva as políticas (inclusive as do crm_owner) junto
COMMIT;
