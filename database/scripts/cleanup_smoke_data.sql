-- =====================================================================
-- scripts/cleanup_smoke_data.sql | limpeza MANUAL dos dados do teste de fumaça (clínicas "teste-smoke-%")
-- Executar : como postgres (BYPASSRLS) com o papel crm_owner herdado (dono das tabelas), por decisão humana:
--              GRANT crm_owner TO postgres;     -- só se o postgres ainda não for membro (uma vez)
--              psql "$ADMIN_URL" -X -v ON_ERROR_STOP=1 -f database/scripts/cleanup_smoke_data.sql
-- Efeito   : apaga TUDO que pertence às clínicas cujo nome começa com 'teste-smoke-' (nenhuma outra clínica é tocada).
--            Os triggers de auditoria e o de "só inserção" do audit_log são desligados só dentro da transação e religados
--            antes do COMMIT. Os objetos do Storage não são tocados aqui (apague pela API do Storage).
-- Segurança: se o escopo tiver 0 clínicas ou mais de 10, ou se algum trigger não voltar ao estado ENABLED, a transação aborta.
-- =====================================================================
BEGIN;

CREATE TEMP TABLE _smoke_clinics ON COMMIT DROP AS
  SELECT id FROM core.clinics WHERE name LIKE 'teste-smoke-%';

SELECT 'clinicas no escopo: ' || count(*) AS escopo,
       'clinicas fora do escopo (intactas): ' || ((SELECT count(*) FROM core.clinics) - count(*)) AS preservadas
  FROM _smoke_clinics;

DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM _smoke_clinics;
  IF n = 0 OR n > 10 THEN RAISE EXCEPTION 'escopo inesperado: % clinicas "teste-smoke-%%"', n; END IF;
END $$;

ALTER TABLE core.audit_log      DISABLE TRIGGER trg_audit_log_append_only;
ALTER TABLE core.patients       DISABLE TRIGGER trg_patients_audit;
ALTER TABLE core.medical_histories DISABLE TRIGGER trg_histories_audit;
ALTER TABLE core.clinical_notes DISABLE TRIGGER trg_notes_audit;
ALTER TABLE core.patient_files  DISABLE TRIGGER trg_files_audit;

DELETE FROM core.audit_log         WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.patient_files     WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.clinical_notes    WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.medical_histories WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.patients          WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.refresh_tokens    WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
UPDATE core.users SET created_by = NULL WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.users             WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.clinics           WHERE id        IN (SELECT id FROM _smoke_clinics);

ALTER TABLE core.audit_log      ENABLE TRIGGER trg_audit_log_append_only;
ALTER TABLE core.patients       ENABLE TRIGGER trg_patients_audit;
ALTER TABLE core.medical_histories ENABLE TRIGGER trg_histories_audit;
ALTER TABLE core.clinical_notes ENABLE TRIGGER trg_notes_audit;
ALTER TABLE core.patient_files  ENABLE TRIGGER trg_files_audit;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_trigger t
              WHERE t.tgrelid::regclass::text LIKE 'core.%'
                AND t.tgname IN ('trg_audit_log_append_only', 'trg_patients_audit', 'trg_histories_audit', 'trg_notes_audit', 'trg_files_audit')
                AND t.tgenabled <> 'O') THEN
    RAISE EXCEPTION 'algum trigger nao voltou ao estado ENABLED';
  END IF;
  IF EXISTS (SELECT 1 FROM core.clinics WHERE name LIKE 'teste-smoke-%') THEN
    RAISE EXCEPTION 'restaram clinicas de teste';
  END IF;
END $$;

SELECT 'clinicas restantes: ' || count(*) AS depois FROM core.clinics;
COMMIT;
