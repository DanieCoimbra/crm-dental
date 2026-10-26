-- =====================================================================
-- tests/budget_items.sql | Fase 3 / F02-budgets
-- Teste 93 da spec global da Fase 3: itens do orçamento. Snapshot de descrição e preço copiados do catálogo (o valor do cliente é
-- sobrescrito e o item não muda quando o preço-base muda); line_total gerado; total recalculado a cada INSERT/UPDATE/DELETE de item;
-- limites (quantidade, posição, 50 itens, desconto de item, teto); posição única e ADIÁVEL (reordenar numa transação).
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 23514 CHECK, teto ou coluna imutável; 23503 FK; 23505 posição repetida (no COMMIT).
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set b1 'f4000000-0000-4000-8000-000000000301'
\set b2 'f4000000-0000-4000-8000-000000000302'
\set b3 'f4000000-0000-4000-8000-000000000303'
\set b4 'f4000000-0000-4000-8000-000000000304'
\set ptmax 'f4000000-0000-4000-8000-0000000003c1'

CREATE FUNCTION pg_temp.q(p_sql text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN EXECUTE p_sql INTO v; RETURN v; END $$;

CREATE FUNCTION pg_temp.expect_eq(p_label text, p_actual text, p_expected text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF p_actual IS DISTINCT FROM p_expected THEN RAISE EXCEPTION '%: esperado %, obtido %', p_label, p_expected, p_actual; END IF;
END $$;

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

SELECT pg_temp.expect_rows('T93 procedimento de preco maximo', pg_temp.ptype_sql(:'ptmax', :'ca', 'Implante', '999999.99', '60', '#445566', :'ua_d'), 1);
SELECT pg_temp.expect_rows('orcamento 1', pg_temp.budget_sql(:'b1', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('orcamento 2', pg_temp.budget_sql(:'b2', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('orcamento 3', pg_temp.budget_sql(:'b3', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('orcamento 4', pg_temp.budget_sql(:'b4', :'ca', :'pa', :'ua_d'), 1);

-- ---------------------------------------------------------------------
-- Snapshot: descrição e preço vêm do catálogo; o valor enviado pelo cliente é sobrescrito
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_rows('T93 item com description e unit_price forjados', format(
  'INSERT INTO core.budget_items (id, clinic_id, budget_id, procedure_type_id, description, unit_price, quantity, position) '
  'VALUES (%L, %L, %L, %L, ''Nome Forjado Joao'', 1.00, 2, 1)', 'f4000000-0000-4000-8000-000000000311', :'ca', :'b1', :'pt1'), 1);
SELECT pg_temp.expect_eq('T93 description copiada do catalogo', pg_temp.q(format('SELECT description FROM core.budget_items WHERE id = %L', 'f4000000-0000-4000-8000-000000000311')), 'Restauracao');
SELECT pg_temp.expect_eq('T93 unit_price copiado do catalogo', pg_temp.q(format('SELECT unit_price::text FROM core.budget_items WHERE id = %L', 'f4000000-0000-4000-8000-000000000311')), '150.00');
SELECT pg_temp.expect_eq('T93 line_total gerado = 2 x 150', pg_temp.q(format('SELECT line_total::text FROM core.budget_items WHERE id = %L', 'f4000000-0000-4000-8000-000000000311')), '300.00');
SELECT pg_temp.expect_eq('T93 total do orcamento = 300', pg_temp.q(format('SELECT total_amount::text FROM core.budgets WHERE id = %L', :'b1')), '300.00');

-- mudar o preço-base NÃO altera o item existente; o item novo usa o preço novo
SELECT pg_temp.expect_rows('T93 preco-base de pt1 sobe para 200', format('UPDATE core.procedure_types SET base_price = 200 WHERE id = %L', :'pt1'), 1);
SELECT pg_temp.expect_eq('T93 item antigo mantem 150', pg_temp.q(format('SELECT unit_price::text FROM core.budget_items WHERE id = %L', 'f4000000-0000-4000-8000-000000000311')), '150.00');
SELECT pg_temp.expect_eq('T93 total do orcamento nao muda', pg_temp.q(format('SELECT total_amount::text FROM core.budgets WHERE id = %L', :'b1')), '300.00');
SELECT pg_temp.expect_rows('T93 item novo de pt1 (preco 200)', pg_temp.item_sql(:'ca', :'b1', :'pt1', '1', '2'), 1);
SELECT pg_temp.expect_eq('T93 total = 300 + 200', pg_temp.q(format('SELECT total_amount::text FROM core.budgets WHERE id = %L', :'b1')), '500.00');

-- total recalculado em UPDATE e DELETE de item
SELECT pg_temp.expect_rows('T93 UPDATE da quantidade', format('UPDATE core.budget_items SET quantity = 3 WHERE id = %L', 'f4000000-0000-4000-8000-000000000311'), 1);
SELECT pg_temp.expect_eq('T93 total = 450 + 200', pg_temp.q(format('SELECT total_amount::text FROM core.budgets WHERE id = %L', :'b1')), '650.00');
SELECT pg_temp.expect_rows('T93 DELETE do item', format('DELETE FROM core.budget_items WHERE id = %L', 'f4000000-0000-4000-8000-000000000311'), 1);
SELECT pg_temp.expect_eq('T93 total = 200', pg_temp.q(format('SELECT total_amount::text FROM core.budgets WHERE id = %L', :'b1')), '200.00');

-- ---------------------------------------------------------------------
-- Imutáveis e FKs
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T93 description imutavel', '23514', format('UPDATE core.budget_items SET description = ''outra'' WHERE budget_id = %L', :'b1'));
SELECT pg_temp.expect_err('T93 unit_price imutavel', '23514', format('UPDATE core.budget_items SET unit_price = 1 WHERE budget_id = %L', :'b1'));
SELECT pg_temp.expect_err('T93 procedure_type_id imutavel', '23514', format('UPDATE core.budget_items SET procedure_type_id = %L WHERE budget_id = %L', :'pt2', :'b1'));
SELECT pg_temp.expect_err('T93 budget_id imutavel', '23514', format('UPDATE core.budget_items SET budget_id = %L WHERE budget_id = %L', :'b2', :'b1'));
SELECT pg_temp.expect_err('T93 procedimento de outra clinica', '23503', pg_temp.item_sql(:'ca', :'b2', :'ptb', '1', '1'));
SELECT pg_temp.expect_err('T93 procedimento inexistente', '23503', pg_temp.item_sql(:'ca', :'b2', gen_random_uuid()::text, '1', '1'));
SELECT pg_temp.expect_err('T93 orcamento inexistente', '23503', pg_temp.item_sql(:'ca', gen_random_uuid()::text, :'pt1', '1', '1'));
SELECT pg_temp.expect_err('T93 item com clinic_id de outra clinica (RLS)', '42501', pg_temp.item_sql(:'cb', :'b2', :'pt1', '1', '1'));
-- procedimento INATIVO ainda é aceito pelo banco (impedir itens novos dele é regra do backend)
SELECT pg_temp.expect_rows('T93 inativa pt2', format('UPDATE core.procedure_types SET active = false WHERE id = %L', :'pt2'), 1);
SELECT pg_temp.expect_rows('T93 item de procedimento inativo passa no banco', pg_temp.item_sql(:'ca', :'b2', :'pt2', '1', '1'), 1);
SELECT pg_temp.expect_eq('T93 total 800', pg_temp.q(format('SELECT total_amount::text FROM core.budgets WHERE id = %L', :'b2')), '800.00');

-- ---------------------------------------------------------------------
-- Limites
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T93 quantidade 0', '23514', pg_temp.item_sql(:'ca', :'b3', :'pt1', '0', '1'));
SELECT pg_temp.expect_err('T93 quantidade 1000', '23514', pg_temp.item_sql(:'ca', :'b3', :'pt1', '1000', '1'));
SELECT pg_temp.expect_err('T93 posicao 0', '23514', pg_temp.item_sql(:'ca', :'b3', :'pt1', '1', '0'));
SELECT pg_temp.expect_err('T93 posicao 51', '23514', pg_temp.item_sql(:'ca', :'b3', :'pt1', '1', '51'));
SELECT pg_temp.expect_err('T93 desconto de item negativo', '23514', pg_temp.item_sql(:'ca', :'b3', :'pt1', '1', '1', '-1'));
SELECT pg_temp.expect_err('T93 desconto de item maior que a linha', '23514', pg_temp.item_sql(:'ca', :'b3', :'pt1', '1', '1', '200.01'));
SELECT pg_temp.expect_rows('T93 desconto de item igual a linha (linha zero) passa', pg_temp.item_sql(:'ca', :'b3', :'pt1', '1', '1', '200'), 1);
SELECT pg_temp.expect_err('T93 linha acima do teto (2 x 999.999,99)', '23514', pg_temp.item_sql(:'ca', :'b3', :'ptmax', '2', '2'));
SELECT pg_temp.expect_rows('T93 linha no teto (1 x 999.999,99)', pg_temp.item_sql(:'ca', :'b3', :'ptmax', '1', '2'), 1);
SELECT pg_temp.expect_err('T93 total acima do teto (999.999,99 + 200)', '23514', pg_temp.item_sql(:'ca', :'b3', :'pt1', '1', '3'));
SELECT pg_temp.expect_eq('T93 total intacto apos a falha', pg_temp.q(format('SELECT total_amount::text FROM core.budgets WHERE id = %L', :'b3')), '999999.99');

-- 50 itens passam; o 51o (posicao 51) falha; posicao repetida falha no COMMIT
SELECT pg_temp.expect_rows('T93 50 itens em um INSERT', format(
  'INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position) SELECT %L, %L, %L, 1, g FROM generate_series(1, 50) g',
  :'ca', :'b4', :'pt1'), 50);
SELECT pg_temp.expect_eq('T93 total de 50 x 200', pg_temp.q(format('SELECT total_amount::text FROM core.budgets WHERE id = %L', :'b4')), '10000.00');
SELECT pg_temp.expect_err('T93 51o item', '23514', pg_temp.item_sql(:'ca', :'b4', :'pt1', '1', '51'));
SELECT pg_temp.expect_err_commit('T93 posicao repetida (no COMMIT)', '23505', ARRAY[pg_temp.item_sql(:'ca', :'b4', :'pt1', '1', '50')]);

-- reordenar troca posicoes dentro de uma transacao (a unicidade e adiavel)
SELECT pg_temp.expect_rows('T93 limpa o b2', format('DELETE FROM core.budget_items WHERE budget_id = %L', :'b2'), 1);
SELECT pg_temp.expect_rows('T93 item A na posicao 1', pg_temp.item_sql(:'ca', :'b2', :'pt1', '1', '1'), 1);
SELECT pg_temp.expect_rows('T93 item B na posicao 2', pg_temp.item_sql(:'ca', :'b2', :'pt1', '2', '2'), 1);
SELECT pg_temp.expect_ok_commit('T93 troca de posicoes numa transacao',
  ARRAY[format('UPDATE core.budget_items SET position = 3 - position WHERE budget_id = %L', :'b2')]);
SELECT pg_temp.expect_eq('T93 o item de quantidade 2 foi para a posicao 1',
  pg_temp.q(format('SELECT quantity::text FROM core.budget_items WHERE budget_id = %L AND position = 1', :'b2')), '2');

RESET SESSION AUTHORIZATION;
ROLLBACK;
\o
SELECT 'budget_items: OK' AS resultado;
