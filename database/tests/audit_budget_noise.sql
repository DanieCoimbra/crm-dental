-- =====================================================================
-- tests/audit_budget_noise.sql | V030 (auditoria de segurança e desempenho de 2026-10-27)
-- Teste 122: a edição de itens de um orçamento NÃO gera uma linha de audit_log por item. O recálculo do total (UPDATE em budgets por
--            linha de item) deixou de ser auditado; mudança de desconto, observação, status ou version continua gerando 1 linha cada;
--            UPDATE sem mudança real não gera; INSERT do orçamento continua gerando 1.
-- Teste 123: core.budget_documents recusa PDF de paciente anonimizado no banco (CR003), antes e depois da V030 só o backend impedia.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : CR003 paciente anonimizado.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set n1 'f4000000-0000-4000-8000-000000000c01'
\set pz 'f4000000-0000-4000-8000-000000000c11'
\set bz 'f4000000-0000-4000-8000-000000000c12'

CREATE FUNCTION pg_temp.ac(p_table text, p_record text, p_action text) RETURNS bigint LANGUAGE sql AS $$
  SELECT count(*) FROM core.audit_log WHERE table_name = p_table AND record_id = p_record::uuid AND action = p_action $$;
CREATE FUNCTION pg_temp.expect_n(p_label text, p_actual bigint, p_expected bigint) RETURNS void LANGUAGE plpgsql AS $$
BEGIN IF p_actual <> p_expected THEN RAISE EXCEPTION '%: esperadas % linhas de auditoria, obtidas %', p_label, p_expected, p_actual; END IF; END $$;

-- ---------------------------------------------------------------------
-- 122: ruído da edição de itens
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('orcamento', pg_temp.budget_sql(:'n1', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('item ' || g, pg_temp.item_sql(:'ca', :'n1', :'pt1', '1', g::text), 1) FROM generate_series(1, 20) g;
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T122 INSERT do orcamento = 1', pg_temp.ac('budgets', :'n1', 'INSERT'), 1);
SELECT pg_temp.expect_n('T122 20 itens inseridos = 0 UPDATEs auditados (antes da V030: 20)', pg_temp.ac('budgets', :'n1', 'UPDATE'), 0);
DO $$
BEGIN
  IF (SELECT total_amount FROM core.budgets WHERE id = 'f4000000-0000-4000-8000-000000000c01') <> 3000.00 THEN
    RAISE EXCEPTION 'T122: o total deve continuar sendo recalculado pelo banco (20 x 150,00)';
  END IF;
END $$;

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('remove os 20 itens', format('DELETE FROM core.budget_items WHERE budget_id = %L', :'n1'), 20);
SELECT pg_temp.expect_rows('item unico', pg_temp.item_sql(:'ca', :'n1', :'pt1', '1', '1'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T122 remover 20 itens e incluir 1 = 0 UPDATEs auditados', pg_temp.ac('budgets', :'n1', 'UPDATE'), 0);
DO $$
BEGIN
  IF (SELECT total_amount FROM core.budgets WHERE id = 'f4000000-0000-4000-8000-000000000c01') <> 150.00 THEN
    RAISE EXCEPTION 'T122: o total deve ser 150,00 depois da substituicao dos itens';
  END IF;
END $$;

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('desconto muda', pg_temp.budget_discount_sql(:'n1', '10'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T122 desconto que muda = 1 UPDATE', pg_temp.ac('budgets', :'n1', 'UPDATE'), 1);
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('desconto igual', pg_temp.budget_discount_sql(:'n1', '10'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T122 UPDATE sem mudanca real = nenhuma linha nova', pg_temp.ac('budgets', :'n1', 'UPDATE'), 1);
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('observacao muda', format('UPDATE core.budgets SET notes = %L WHERE id = %L', 'combinado', :'n1'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T122 observacao que muda = 1 UPDATE', pg_temp.ac('budgets', :'n1', 'UPDATE'), 2);
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('version sobe', format('UPDATE core.budgets SET version = version + 1 WHERE id = %L', :'n1'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T122 version que sobe (edicao de itens pelo backend) = 1 UPDATE', pg_temp.ac('budgets', :'n1', 'UPDATE'), 3);
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('envia', pg_temp.budget_status_sql(:'n1', 'SENT'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T122 status que muda = 1 UPDATE', pg_temp.ac('budgets', :'n1', 'UPDATE'), 4);

-- os dois triggers existem (INSERT/DELETE sempre; UPDATE com WHEN) e o antigo saiu
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'core.budgets'::regclass AND tgname = 'trg_budgets_audit') THEN
    RAISE EXCEPTION 'T122: trg_budgets_audit deveria ter sido substituido';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'core.budgets'::regclass AND tgname = 'trg_budgets_audit_insdel' AND NOT tgisinternal)
     OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'core.budgets'::regclass AND tgname = 'trg_budgets_audit_upd' AND NOT tgisinternal
                      AND pg_get_triggerdef(oid) LIKE '%WHEN%') THEN
    RAISE EXCEPTION 'T122: faltam trg_budgets_audit_insdel ou trg_budgets_audit_upd (com WHEN)';
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- 123: PDF de paciente anonimizado
-- ---------------------------------------------------------------------
:ctx_a_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, active, deactivated_at, created_by)
  VALUES (:'pz', :'ca', 'Paciente do PDF', '1980-03-01', '11999990031', true, NULL, :'ua_d');
INSERT INTO core.budgets (id, clinic_id, patient_id, created_by) VALUES (:'bz', :'ca', :'pz', :'ua_d');
INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position) VALUES (:'ca', :'bz', :'pt1', 1, 1);
UPDATE core.budgets SET status = 'SENT' WHERE id = :'bz';

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T123 PDF de paciente ativo continua passando', pg_temp.doc_sql(:'ca', :'bz', :'pz', :'ua_d'), 1);
RESET SESSION AUTHORIZATION;
:ctx_a_d \gset
UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = :'pz';
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT core.anonymize_patient(:'pz');
SELECT pg_temp.expect_err('T123 PDF de paciente anonimizado (banco)', 'CR003', pg_temp.doc_sql(:'ca', :'bz', :'pz', :'ua_d'));
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  IF (SELECT count(*) FROM core.budget_documents WHERE budget_id = 'f4000000-0000-4000-8000-000000000c12') <> 1 THEN
    RAISE EXCEPTION 'T123: so o PDF anterior a anonimizacao pode existir';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'audit_budget_noise: OK' AS resultado;
