-- =====================================================================
-- tests/budget_documents.sql | Fase 3 / F03-budget-documents
-- Teste 110 da spec global da Fase 3: metadados do PDF do orçamento no Storage. Só para orçamento fora de DRAFT (CR008); revisão
-- atribuída pelo banco (1, 2, 3...; conta as removidas); storage_key no formato <clinic>/<patient>/budgets/<uuid>; sha256, tipo e
-- tamanho validados; todas as colunas imutáveis exceto deleted_at, que só vai de nulo a um instante; sem DELETE; recepção lê e não
-- grava. A concorrência de duas revisões está em commercial_concurrency.sh.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : CR008 orçamento em DRAFT; 23514 CHECK ou imutável; 23505 storage_key repetida; 23503 FK; 42501 papel, autoria, privilégio.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set bd 'f4000000-0000-4000-8000-000000000801'
\set bs 'f4000000-0000-4000-8000-000000000802'
\set bs2 'f4000000-0000-4000-8000-000000000803'
\set d1 'f4000000-0000-4000-8000-000000000811'
\set d2 'f4000000-0000-4000-8000-000000000812'
\set d3 'f4000000-0000-4000-8000-000000000813'

CREATE FUNCTION pg_temp.q(p_sql text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN EXECUTE p_sql INTO v; RETURN v; END $$;
CREATE FUNCTION pg_temp.expect_eq(p_label text, p_actual text, p_expected text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF p_actual IS DISTINCT FROM p_expected THEN RAISE EXCEPTION '%: esperado %, obtido %', p_label, p_expected, p_actual; END IF;
END $$;
-- INSERT completo com chave e demais colunas explícitas
CREATE FUNCTION pg_temp.doc_full_sql(p_budget text, p_patient text, p_key text, p_type text, p_size text, p_sha text, p_user text, p_revision text DEFAULT NULL)
  RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.budget_documents (clinic_id, budget_id, patient_id, revision, storage_key, content_type, size_bytes, sha256, created_by) '
                'VALUES (''f1000000-0000-4000-8000-00000000000a'', %L, %L, COALESCE(%L::smallint, 1), %L, %L, %L, %L, %L)',
                p_budget, p_patient, p_revision, p_key, p_type, p_size, p_sha, p_user) $$;

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('orcamento DRAFT', pg_temp.budget_sql(:'bd', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('item do DRAFT', pg_temp.item_sql(:'ca', :'bd', :'pt1', '1', '1'), 1);
SELECT pg_temp.expect_rows('orcamento SENT', pg_temp.budget_sql(:'bs', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('item do SENT', pg_temp.item_sql(:'ca', :'bs', :'pt1', '1', '1'), 1);
SELECT pg_temp.expect_rows('envia', pg_temp.budget_status_sql(:'bs', 'SENT'), 1);
SELECT pg_temp.expect_rows('orcamento do outro paciente', pg_temp.budget_sql(:'bs2', :'ca', :'pa2', :'ua_d'), 1);
SELECT pg_temp.expect_rows('item do outro', pg_temp.item_sql(:'ca', :'bs2', :'pt1', '1', '1'), 1);
SELECT pg_temp.expect_rows('envia o outro', pg_temp.budget_status_sql(:'bs2', 'SENT'), 1);

-- ---------------------------------------------------------------------
-- T110: só depois de enviar
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T110 PDF de orcamento DRAFT', 'CR008', pg_temp.doc_sql(:'ca', :'bd', :'pa', :'ua_d'));

-- revisões atribuídas pelo banco (o valor do cliente é ignorado)
SELECT pg_temp.expect_rows('T110 revisao 1', pg_temp.doc_sql(:'ca', :'bs', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('T110 revisao 2', pg_temp.doc_sql(:'ca', :'bs', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('T110 revisao enviada como 99 e ignorada', pg_temp.doc_full_sql(:'bs', :'pa',
  :'ca' || '/' || :'pa' || '/budgets/' || gen_random_uuid()::text, 'application/pdf', '1000', repeat('b', 64), :'ua_d', '99'), 1);
SELECT pg_temp.expect_eq('T110 revisoes 1,2,3', pg_temp.q(format('SELECT string_agg(revision::text, '','' ORDER BY revision) FROM core.budget_documents WHERE budget_id = %L', :'bs')), '1,2,3');
SELECT pg_temp.expect_rows('T110 revisao 1 do outro orcamento (contagem por orcamento)', pg_temp.doc_sql(:'ca', :'bs2', :'pa2', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T110 outro orcamento comeca em 1', pg_temp.q(format('SELECT max(revision)::text FROM core.budget_documents WHERE budget_id = %L', :'bs2')), '1');

-- storage_key, sha256, tipo e tamanho
SELECT pg_temp.expect_err('T110 chave de outra clinica', '23514', pg_temp.doc_full_sql(:'bs', :'pa',
  :'cb' || '/' || :'pa' || '/budgets/' || gen_random_uuid()::text, 'application/pdf', '1000', repeat('a', 64), :'ua_d'));
SELECT pg_temp.expect_err('T110 chave de outro paciente', '23514', pg_temp.doc_full_sql(:'bs', :'pa',
  :'ca' || '/' || :'pa2' || '/budgets/' || gen_random_uuid()::text, 'application/pdf', '1000', repeat('a', 64), :'ua_d'));
SELECT pg_temp.expect_err('T110 chave sem o prefixo budgets/', '23514', pg_temp.doc_full_sql(:'bs', :'pa',
  :'ca' || '/' || :'pa' || '/' || gen_random_uuid()::text, 'application/pdf', '1000', repeat('a', 64), :'ua_d'));
SELECT pg_temp.expect_err('T110 chave com uuid mal formado', '23514', pg_temp.doc_full_sql(:'bs', :'pa',
  :'ca' || '/' || :'pa' || '/budgets/nao-e-uuid', 'application/pdf', '1000', repeat('a', 64), :'ua_d'));
SELECT pg_temp.expect_err('T110 sha256 com maiuscula', '23514', pg_temp.doc_full_sql(:'bs', :'pa',
  :'ca' || '/' || :'pa' || '/budgets/' || gen_random_uuid()::text, 'application/pdf', '1000', repeat('A', 64), :'ua_d'));
SELECT pg_temp.expect_err('T110 sha256 de 63 caracteres', '23514', pg_temp.doc_full_sql(:'bs', :'pa',
  :'ca' || '/' || :'pa' || '/budgets/' || gen_random_uuid()::text, 'application/pdf', '1000', repeat('a', 63), :'ua_d'));
SELECT pg_temp.expect_err('T110 sha256 nao hexadecimal', '23514', pg_temp.doc_full_sql(:'bs', :'pa',
  :'ca' || '/' || :'pa' || '/budgets/' || gen_random_uuid()::text, 'application/pdf', '1000', repeat('g', 64), :'ua_d'));
SELECT pg_temp.expect_err('T110 tipo diferente de PDF', '23514', pg_temp.doc_full_sql(:'bs', :'pa',
  :'ca' || '/' || :'pa' || '/budgets/' || gen_random_uuid()::text, 'image/png', '1000', repeat('a', 64), :'ua_d'));
SELECT pg_temp.expect_err('T110 0 byte', '23514', pg_temp.doc_full_sql(:'bs', :'pa',
  :'ca' || '/' || :'pa' || '/budgets/' || gen_random_uuid()::text, 'application/pdf', '0', repeat('a', 64), :'ua_d'));
SELECT pg_temp.expect_err('T110 6 MB', '23514', pg_temp.doc_full_sql(:'bs', :'pa',
  :'ca' || '/' || :'pa' || '/budgets/' || gen_random_uuid()::text, 'application/pdf', '6291456', repeat('a', 64), :'ua_d'));
SELECT pg_temp.expect_rows('T110 5 MB exatos passam', pg_temp.doc_full_sql(:'bs', :'pa',
  :'ca' || '/' || :'pa' || '/budgets/' || gen_random_uuid()::text, 'application/pdf', '5242880', repeat('c', 64), :'ua_d'), 1);
SELECT pg_temp.expect_rows('T110 chave fixa (para repetir)', pg_temp.doc_full_sql(:'bs2', :'pa2',
  :'ca' || '/' || :'pa2' || '/budgets/f4000000-0000-4000-8000-0000000008ff', 'application/pdf', '1000', repeat('d', 64), :'ua_d'), 1);
SELECT pg_temp.expect_err('T110 storage_key repetida', '23505', pg_temp.doc_full_sql(:'bs2', :'pa2',
  :'ca' || '/' || :'pa2' || '/budgets/f4000000-0000-4000-8000-0000000008ff', 'application/pdf', '1000', repeat('d', 64), :'ua_d'));
SELECT pg_temp.expect_err('T110 paciente diferente do orcamento (FK composta)', '23503', pg_temp.doc_sql(:'ca', :'bs', :'pa2', :'ua_d'));
SELECT pg_temp.expect_err('T110 orcamento inexistente', '23503', pg_temp.doc_sql(:'ca', gen_random_uuid()::text, :'pa', :'ua_d'));

-- imutabilidade e deleted_at de mão única
SELECT pg_temp.expect_err('T110 UPDATE de sha256 (sem privilegio de coluna)', '42501', format('UPDATE core.budget_documents SET sha256 = repeat(''e'', 64) WHERE budget_id = %L', :'bs'));
SELECT pg_temp.expect_err('T110 UPDATE de storage_key (sem privilegio de coluna)', '42501', format('UPDATE core.budget_documents SET storage_key = ''x'' WHERE budget_id = %L', :'bs'));
SELECT pg_temp.expect_err('T110 UPDATE de revision', '42501', format('UPDATE core.budget_documents SET revision = 9 WHERE budget_id = %L', :'bs'));
SELECT pg_temp.expect_err('T110 DELETE', '42501', format('DELETE FROM core.budget_documents WHERE budget_id = %L', :'bs'));
SELECT pg_temp.expect_rows('T110 marca a revisao 3 como removida', format(
  'UPDATE core.budget_documents SET deleted_at = now() WHERE budget_id = %L AND revision = 3', :'bs'), 1);
SELECT pg_temp.expect_err('T110 deleted_at nao volta a nulo', '23514', format('UPDATE core.budget_documents SET deleted_at = NULL WHERE budget_id = %L AND revision = 3', :'bs'));
SELECT pg_temp.expect_err('T110 deleted_at nao muda depois de preenchido', '23514', format(
  'UPDATE core.budget_documents SET deleted_at = now() + interval ''1 day'' WHERE budget_id = %L AND revision = 3', :'bs'));
SELECT pg_temp.expect_eq('T110 revisao ativa = maior revisao com deleted_at nulo (a 3 foi removida; a 4 e a de 5 MB)',
  pg_temp.q(format('SELECT revision::text FROM core.budget_documents WHERE budget_id = %L AND deleted_at IS NULL ORDER BY revision DESC LIMIT 1', :'bs')), '4');
SELECT pg_temp.expect_rows('T110 nova revisao conta a removida (nao reaproveita o 3)', pg_temp.doc_sql(:'ca', :'bs', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T110 nova revisao = 5', pg_temp.q(format('SELECT max(revision)::text FROM core.budget_documents WHERE budget_id = %L', :'bs')), '5');

-- papéis
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T110 recepcao le os metadados (5 de bs + 2 de bs2)', 'SELECT 1 FROM core.budget_documents', 7);
SELECT pg_temp.expect_err('T110 recepcao nao registra PDF', '42501', pg_temp.doc_sql(:'ca', :'bs', :'pa', :'ua_r'));
SELECT pg_temp.expect_rows('T110 recepcao nao marca deleted_at (0 linhas)', format('UPDATE core.budget_documents SET deleted_at = now() WHERE budget_id = %L AND revision = 1', :'bs'), 0);
:ctx_a_d \gset
SELECT pg_temp.expect_err('T110 autoria: created_by diferente de app.user_id', '42501', pg_temp.doc_sql(:'ca', :'bs', :'pa', :'ua_r'));
:ctx_a_norole \gset
SELECT pg_temp.expect_rows('T110 sem papel: 0 linhas', 'SELECT 1 FROM core.budget_documents', 0);
:ctx_none \gset
SELECT pg_temp.expect_rows('T110 sem sessao: 0 linhas', 'SELECT 1 FROM core.budget_documents', 0);

RESET SESSION AUTHORIZATION;
ROLLBACK;
\o
SELECT 'budget_documents: OK' AS resultado;
