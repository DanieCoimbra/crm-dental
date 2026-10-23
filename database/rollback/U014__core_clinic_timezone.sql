-- =====================================================================
-- U014__core_clinic_timezone.sql | reversão MANUAL de V014 (nunca automática; APAGA o fuso configurado de cada clínica)
-- Executar : como crm_owner, por decisão humana, DEPOIS de reverter V015–V021. O backend da Fase 2 deixa de poder ler o fuso.
-- Observação: a extensão btree_gist é do bootstrap (não é migration); ver rollback/README.md.
-- =====================================================================
BEGIN;
DROP TRIGGER IF EXISTS trg_clinics_validate_timezone ON core.clinics;
DROP FUNCTION IF EXISTS core.validate_clinic_timezone();
ALTER TABLE core.clinics DROP CONSTRAINT IF EXISTS ck_clinics_timezone;
ALTER TABLE core.clinics DROP COLUMN IF EXISTS timezone;
COMMIT;
