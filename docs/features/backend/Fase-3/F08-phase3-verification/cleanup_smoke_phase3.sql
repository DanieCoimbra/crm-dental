-- =====================================================================
-- cleanup_smoke_phase3.sql | limpeza MANUAL dos dados do smoke da Fase 3 (clínicas "teste-smoke-f3-%")
-- Executar : no SQL Editor do Supabase, como postgres (decisão humana; o .env do backend não tem a senha do dono).
-- Efeito   : apaga TUDO que pertence às clínicas cujo nome começa com 'teste-smoke-f3-' (nenhuma outra é tocada):
--            pagamentos (a tabela é só de inserção, nega DELETE até ao dono), parcelas, PDFs, itens, orçamentos, procedimentos,
--            agenda, prontuário, auditoria, usuários e a clínica. Os objetos do Storage já foram apagados pela anonimização
--            do próprio smoke (storageCleanup = COMPLETED); se o smoke tiver parado antes, apague o prefixo da clínica no Storage.
-- Como     : session_replication_role = replica, SÓ dentro da transação (SET LOCAL), suspende FKs e triggers (auditoria,
--            "só inserção"); volta sozinho no COMMIT/ROLLBACK. Nada fora do escopo é apagado.
-- Segurança: aborta se o escopo tiver 0 ou mais de 10 clínicas, se restar linha das clínicas de teste ou se algum trigger de
--            tabela do schema core estiver desabilitado.
-- Este arquivo vive em docs/ (não em database/): a F08 não altera o banco.
-- =====================================================================
BEGIN;
SET LOCAL session_replication_role = replica;

CREATE TEMP TABLE _smoke_clinics ON COMMIT DROP AS
  SELECT id FROM core.clinics WHERE name LIKE 'teste-smoke-f3-%';

SELECT 'clinicas no escopo: ' || count(*) AS escopo,
       'clinicas fora do escopo (intactas): ' || ((SELECT count(*) FROM core.clinics) - count(*)) AS preservadas
  FROM _smoke_clinics;

DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM _smoke_clinics;
  IF n = 0 OR n > 10 THEN RAISE EXCEPTION 'escopo inesperado: % clinicas "teste-smoke-f3-%%"', n; END IF;
END $$;

DELETE FROM core.payments          WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.installments      WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.budget_documents  WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.budget_items      WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.budgets           WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.appointments      WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.procedure_types   WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.business_hours    WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.clinic_holidays   WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.audit_log         WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.patient_files     WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.clinical_notes    WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.medical_histories WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.patients          WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.refresh_tokens    WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
UPDATE core.users SET created_by = NULL WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.users             WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.clinics           WHERE id        IN (SELECT id FROM _smoke_clinics);

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.clinics WHERE name LIKE 'teste-smoke-f3-%') THEN
    RAISE EXCEPTION 'restaram clinicas de teste';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = 'core' AND NOT t.tgisinternal AND t.tgenabled = 'D') THEN
    RAISE EXCEPTION 'algum trigger do schema core ficou desabilitado';
  END IF;
END $$;

SELECT 'clinicas restantes: ' || count(*) AS depois FROM core.clinics;
COMMIT;
