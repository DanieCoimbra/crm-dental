-- =====================================================================
-- U018__core_holidays_seed_2026_2030.sql | reversão MANUAL de V018 (nunca automática)
-- Executar : como crm_owner (a política hol_owner_del da V017 permite o DELETE sob FORCE), por decisão humana, ANTES de U017.
-- Remove o intervalo do seed (2026-01-01 a 2030-12-31). Se outras migrations de seed adicionaram datas nesse intervalo, elas
-- também saem: reaplique-as depois.
-- =====================================================================
DELETE FROM core.holidays WHERE holiday_date BETWEEN DATE '2026-01-01' AND DATE '2030-12-31';
