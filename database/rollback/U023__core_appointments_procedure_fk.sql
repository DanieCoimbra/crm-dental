-- =====================================================================
-- U023__core_appointments_procedure_fk.sql | reversão MANUAL de V023 (sempre segura: só remove a FK e o índice)
-- Executar : como crm_owner, por decisão humana, ANTES de U022. A coluna procedure_type_id e os valores gravados permanecem.
-- =====================================================================
BEGIN;
DROP INDEX IF EXISTS core.ix_appt_procedure;
ALTER TABLE core.appointments DROP CONSTRAINT IF EXISTS fk_appt_procedure;
COMMIT;
