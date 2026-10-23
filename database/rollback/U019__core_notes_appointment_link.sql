-- =====================================================================
-- U019__core_notes_appointment_link.sql | reversão MANUAL de V019 (nunca automática; APAGA os vínculos nota-atendimento)
-- Executar : como crm_owner, por decisão humana, ANTES de U015. Só é seguro antes de haver vínculos; depois, restaure do pg_dump.
-- As notas em si (cifradas) não são tocadas: só a coluna appointment_id, a FK e o índice saem.
-- =====================================================================
BEGIN;
DROP INDEX IF EXISTS core.ix_notes_appointment;
ALTER TABLE core.clinical_notes DROP CONSTRAINT IF EXISTS fk_notes_appointment;
ALTER TABLE core.clinical_notes DROP COLUMN IF EXISTS appointment_id;
COMMIT;
