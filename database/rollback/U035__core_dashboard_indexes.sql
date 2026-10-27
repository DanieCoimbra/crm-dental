-- =====================================================================
-- U035__core_dashboard_indexes.sql | reversão MANUAL de V035 (nunca automática)
-- Executar : como crm_owner, por decisão humana. Remove os índices do dashboard; nenhum dado é perdido.
-- =====================================================================
BEGIN;
DROP INDEX IF EXISTS core.ix_patients_clinic_active;
DROP INDEX IF EXISTS core.ix_appt_completed_patient;
COMMIT;
