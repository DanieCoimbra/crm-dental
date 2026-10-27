-- =====================================================================
-- cleanup_smoke_phase3_phase4.sql | limpeza MANUAL dos dados dos smokes da Fase 3 e da Fase 4 (clínicas "teste-smoke-f3-%")
-- Executar : no SQL Editor do Supabase, como postgres (decisão humana). Cole o arquivo inteiro e rode uma vez.
-- Efeito   : apaga TUDO que pertence às clínicas cujo nome começa com 'teste-smoke-f3-' (inclui 'teste-smoke-f3-p4-...' e
--            'teste-smoke-f3-p4-cr004-...'; nenhuma outra clínica é tocada): pagamentos (a tabela é só de inserção), parcelas, PDFs,
--            itens, orçamentos, procedimentos, documentos clínicos, agenda, prontuário, auditoria, usuários e a clínica.
-- Como     : session_replication_role = replica, SÓ dentro da transação (SET LOCAL): suspende FKs e triggers (auditoria,
--            "só inserção", imutabilidade dos documentos). Volta sozinho no COMMIT/ROLLBACK.
-- Segurança: aborta (e desfaz tudo) se o escopo tiver 0 ou mais de 10 clínicas, se restar linha das clínicas de teste ou se algum
--            trigger de tabela do schema core ficar desabilitado.
-- Storage  : os PDFs de orçamento do smoke já foram apagados pela anonimização (storageCleanup = COMPLETED). Documentos clínicos
--            não têm objeto no Storage (o PDF é gerado sob demanda).
-- Este arquivo não altera o schema; apaga só dados de teste.
-- =====================================================================
BEGIN;
SET LOCAL session_replication_role = replica;

CREATE TEMP TABLE _smoke_clinics ON COMMIT DROP AS
  SELECT id, name FROM core.clinics WHERE name LIKE 'teste-smoke-f3-%';

-- Conferência do escopo (aparece no resultado): quais clínicas serão apagadas e quantas ficam intactas
SELECT name AS clinica_no_escopo FROM _smoke_clinics ORDER BY name;
SELECT 'clinicas no escopo: ' || count(*) AS escopo,
       'clinicas fora do escopo (intactas): ' || ((SELECT count(*) FROM core.clinics) - count(*)) AS preservadas
  FROM _smoke_clinics;

DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM _smoke_clinics;
  IF n = 0 OR n > 10 THEN RAISE EXCEPTION 'escopo inesperado: % clinicas "teste-smoke-f3-%%"', n; END IF;
END $$;

DELETE FROM core.payments           WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.installments       WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.budget_documents   WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.budget_items       WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.budgets            WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.clinical_documents WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.appointments       WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.procedure_types    WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.business_hours     WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.clinic_holidays    WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.audit_log          WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.patient_files      WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.clinical_notes     WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.medical_histories  WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.patients           WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.refresh_tokens     WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
UPDATE core.users SET created_by = NULL WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.users              WHERE clinic_id IN (SELECT id FROM _smoke_clinics);
DELETE FROM core.clinics            WHERE id        IN (SELECT id FROM _smoke_clinics);

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.clinics WHERE name LIKE 'teste-smoke-f3-%') THEN
    RAISE EXCEPTION 'restaram clinicas de teste';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = 'core' AND NOT t.tgisinternal AND t.tgenabled = 'D') THEN
    RAISE EXCEPTION 'algum trigger do schema core ficou desabilitado';
  END IF;
  -- nenhuma linha órfã das tabelas principais para clínicas que não existem mais
  IF EXISTS (SELECT 1 FROM core.patients p WHERE NOT EXISTS (SELECT 1 FROM core.clinics c WHERE c.id = p.clinic_id))
     OR EXISTS (SELECT 1 FROM core.clinical_documents d WHERE NOT EXISTS (SELECT 1 FROM core.clinics c WHERE c.id = d.clinic_id))
     OR EXISTS (SELECT 1 FROM core.audit_log a WHERE NOT EXISTS (SELECT 1 FROM core.clinics c WHERE c.id = a.clinic_id)) THEN
    RAISE EXCEPTION 'restaram linhas orfas de clinicas apagadas';
  END IF;
END $$;

SELECT 'clinicas restantes: ' || count(*) AS depois FROM core.clinics;
COMMIT;
