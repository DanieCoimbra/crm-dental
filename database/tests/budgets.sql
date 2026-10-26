-- =====================================================================
-- tests/budgets.sql | Fase 3 / F02-budgets
-- Testes 91 (numeração, uma conexão), 92 (nasce DRAFT; colunas do banco), 94 (descontos), 97 (paciente e atendimento) e 98 (papéis) da
-- spec global da Fase 3. A numeração com DUAS conexões está em commercial_concurrency.sh; itens em budget_items.sql; congelamento e
-- status em budget_status.sql.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : CR008 status inválido; CR003/CR005 paciente anonimizado/inativo; 23514 CHECK; 23503 FK; 42501 papel ou autoria.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set b1 'f4000000-0000-4000-8000-000000000201'
\set b2 'f4000000-0000-4000-8000-000000000202'
\set b3 'f4000000-0000-4000-8000-000000000203'
\set b4 'f4000000-0000-4000-8000-000000000204'
\set b5 'f4000000-0000-4000-8000-000000000205'
\set bb 'f4000000-0000-4000-8000-0000000002b1'
\set ap_a 'f4000000-0000-4000-8000-000000000211'
\set ap_a2 'f4000000-0000-4000-8000-000000000212'

-- agendamentos do paciente pa e do paciente pa2 (fixtures como administrador)
:ctx_a_d \gset
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by) VALUES
  (:'ap_a',  :'ca', :'pa',  '2031-09-01 08:00+00', '2031-09-01 08:30+00', 'SCHEDULED', :'ua_d'),
  (:'ap_a2', :'ca', :'pa2', '2031-09-01 09:00+00', '2031-09-01 09:30+00', 'SCHEDULED', :'ua_d');

CREATE FUNCTION pg_temp.budget_field(p_id text, p_field text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN
  EXECUTE format('SELECT %I::text FROM core.budgets WHERE id = %L', p_field, p_id) INTO v;
  RETURN v;
END $$;

CREATE FUNCTION pg_temp.expect_eq(p_label text, p_actual text, p_expected text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF p_actual IS DISTINCT FROM p_expected THEN
    RAISE EXCEPTION '%: esperado %, obtido %', p_label, p_expected, p_actual;
  END IF;
END $$;

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- ---------------------------------------------------------------------
-- T91: numeração sequencial por clínica; o valor do cliente é ignorado
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_rows('T91 orcamento 1', pg_temp.budget_sql(:'b1', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('T91 orcamento 2', pg_temp.budget_sql(:'b2', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('T91 numero enviado pelo cliente (99) e ignorado', format(
  'INSERT INTO core.budgets (id, clinic_id, number, patient_id, created_by) VALUES (%L, %L, 99, %L, %L)', :'b3', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T91 numero do 1o', pg_temp.budget_field(:'b1', 'number'), '1');
SELECT pg_temp.expect_eq('T91 numero do 2o', pg_temp.budget_field(:'b2', 'number'), '2');
SELECT pg_temp.expect_eq('T91 numero do 3o (99 ignorado)', pg_temp.budget_field(:'b3', 'number'), '3');
:ctx_b_d \gset
SELECT pg_temp.expect_rows('T91 clinica B', pg_temp.budget_sql(:'bb', :'cb', :'pb', :'ub_d'), 1);
SELECT pg_temp.expect_eq('T91 clinica B recomeca em 1', pg_temp.budget_field(:'bb', 'number'), '1');
:ctx_a_d \gset
SELECT pg_temp.expect_err('T91 number imutavel', '23514', format('UPDATE core.budgets SET number = 50 WHERE id = %L', :'b1'));

-- ---------------------------------------------------------------------
-- T92: nasce DRAFT; total, carimbos e número são do banco
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T92 criar ja SENT', 'CR008', format(
  'INSERT INTO core.budgets (id, clinic_id, patient_id, status, created_by) VALUES (gen_random_uuid(), %L, %L, ''SENT'', %L)', :'ca', :'pa', :'ua_d'));
SELECT pg_temp.expect_err('T92 criar ja APPROVED', 'CR008', format(
  'INSERT INTO core.budgets (id, clinic_id, patient_id, status, created_by) VALUES (gen_random_uuid(), %L, %L, ''APPROVED'', %L)', :'ca', :'pa', :'ua_d'));
SELECT pg_temp.expect_err('T92 desconto no INSERT (total negativo)', '23514', format(
  'INSERT INTO core.budgets (id, clinic_id, patient_id, discount_amount, created_by) VALUES (gen_random_uuid(), %L, %L, 10, %L)', :'ca', :'pa', :'ua_d'));
SELECT pg_temp.expect_rows('T92 total, carimbos e status enviados pelo cliente sao ignorados', format(
  'INSERT INTO core.budgets (id, clinic_id, patient_id, total_amount, sent_at, decided_at, created_by) VALUES (%L, %L, %L, 777, now(), now(), %L)',
  :'b4', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T92 total zerado pelo banco', pg_temp.budget_field(:'b4', 'total_amount'), '0.00');
SELECT pg_temp.expect_eq('T92 sent_at nulo', pg_temp.budget_field(:'b4', 'sent_at'), NULL);
SELECT pg_temp.expect_eq('T92 decided_at nulo', pg_temp.budget_field(:'b4', 'decided_at'), NULL);
SELECT pg_temp.expect_eq('T92 status DRAFT', pg_temp.budget_field(:'b4', 'status'), 'DRAFT');
-- CHECKs de coluna
SELECT pg_temp.expect_err('T92 notes so com espacos', '23514', format('UPDATE core.budgets SET notes = ''   '' WHERE id = %L', :'b4'));
SELECT pg_temp.expect_err('T92 notes com mais de 500 caracteres', '22001', format('UPDATE core.budgets SET notes = repeat(''x'', 501) WHERE id = %L', :'b4'));
SELECT pg_temp.expect_rows('T92 notes e validade em DRAFT', format(
  'UPDATE core.budgets SET notes = ''obs'', valid_until = DATE ''2031-12-31'' WHERE id = %L', :'b4'), 1);
SELECT pg_temp.expect_err('T92 validade anterior a 2000', '23514', format('UPDATE core.budgets SET valid_until = DATE ''1999-12-31'' WHERE id = %L', :'b4'));
-- sem DELETE
SELECT pg_temp.expect_err('T92 crm_app nao apaga orcamento', '42501', format('DELETE FROM core.budgets WHERE id = %L', :'b4'));

-- ---------------------------------------------------------------------
-- T94: desconto do orçamento (e dos itens) -- total = soma(q x p - desconto do item) - desconto do orcamento
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_rows('T94 item 2 x pt1 (300)', pg_temp.item_sql(:'ca', :'b1', :'pt1', '2', '1'), 1);
SELECT pg_temp.expect_eq('T94 total 300', pg_temp.budget_field(:'b1', 'total_amount'), '300.00');
SELECT pg_temp.expect_err('T94 desconto maior que a soma', '23514', pg_temp.budget_discount_sql(:'b1', '300.01'));
SELECT pg_temp.expect_rows('T94 desconto igual a soma (total zero) passa em DRAFT', pg_temp.budget_discount_sql(:'b1', '300'), 1);
SELECT pg_temp.expect_eq('T94 total zero', pg_temp.budget_field(:'b1', 'total_amount'), '0.00');
SELECT pg_temp.expect_rows('T94 desconto 50', pg_temp.budget_discount_sql(:'b1', '50'), 1);
SELECT pg_temp.expect_eq('T94 total 250', pg_temp.budget_field(:'b1', 'total_amount'), '250.00');
SELECT pg_temp.expect_rows('T94 segundo item pt2 x 1 com desconto de item 100 (700)', pg_temp.item_sql(:'ca', :'b1', :'pt2', '1', '2', '100'), 1);
SELECT pg_temp.expect_eq('T94 total = (300 + 700) - 50', pg_temp.budget_field(:'b1', 'total_amount'), '950.00');
SELECT pg_temp.expect_err('T94 desconto negativo', '23514', pg_temp.budget_discount_sql(:'b1', '-1'));
SELECT pg_temp.expect_err('T94 desconto acima do teto', '23514', pg_temp.budget_discount_sql(:'b1', '1000000'));
-- remover o item reduz o total; remover tudo com desconto > 0 faz o total ficar negativo e a remoção falha
SELECT pg_temp.expect_rows('T94 remove o 2o item', format('DELETE FROM core.budget_items WHERE budget_id = %L AND position = 2', :'b1'), 1);
SELECT pg_temp.expect_eq('T94 total volta a 250', pg_temp.budget_field(:'b1', 'total_amount'), '250.00');
SELECT pg_temp.expect_err('T94 remover o ultimo item com desconto > 0 deixaria o total negativo', '23514',
  format('DELETE FROM core.budget_items WHERE budget_id = %L', :'b1'));
SELECT pg_temp.expect_eq('T94 total intacto apos a falha', pg_temp.budget_field(:'b1', 'total_amount'), '250.00');

-- ---------------------------------------------------------------------
-- T97: paciente e atendimento de origem
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T97 paciente inativo', 'CR005', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'pa_off', :'ua_d'));
SELECT pg_temp.expect_err('T97 paciente anonimizado', 'CR003', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'pa_anon', :'ua_d'));
SELECT pg_temp.expect_err('T97 paciente de outra clinica', '23503', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'pb', :'ua_d'));
SELECT pg_temp.expect_rows('T97 atendimento do mesmo paciente', format(
  'INSERT INTO core.budgets (id, clinic_id, patient_id, appointment_id, created_by) VALUES (%L, %L, %L, %L, %L)', :'b5', :'ca', :'pa', :'ap_a', :'ua_d'), 1);
SELECT pg_temp.expect_err('T97 atendimento de OUTRO paciente (mesma clinica)', '23503', format(
  'INSERT INTO core.budgets (id, clinic_id, patient_id, appointment_id, created_by) VALUES (gen_random_uuid(), %L, %L, %L, %L)', :'ca', :'pa', :'ap_a2', :'ua_d'));
SELECT pg_temp.expect_err('T97 troca de atendimento por UPDATE (de outro paciente)', '23503',
  format('UPDATE core.budgets SET appointment_id = %L WHERE id = %L', :'ap_a2', :'b5'));
SELECT pg_temp.expect_rows('T97 troca para o atendimento correto, em DRAFT', format('UPDATE core.budgets SET appointment_id = NULL WHERE id = %L', :'b5'), 1);
-- orçamento existente de paciente inativado segue editável
RESET SESSION AUTHORIZATION;
UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = :'pa';
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T97 orcamento de paciente inativado segue editavel', format('UPDATE core.budgets SET notes = ''ainda editavel'' WHERE id = %L', :'b5'), 1);
SELECT pg_temp.expect_rows('T97 e itens podem ser incluidos', pg_temp.item_sql(:'ca', :'b5', :'pt1', '1', '1'), 1);
SELECT pg_temp.expect_rows('T97 e pode ser enviado', pg_temp.budget_status_sql(:'b5', 'SENT'), 1);
SELECT pg_temp.expect_err('T97 mas orcamento NOVO do mesmo paciente inativado falha', 'CR005', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d'));

-- ---------------------------------------------------------------------
-- T98: papéis
-- ---------------------------------------------------------------------
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T98 recepcao le os orcamentos da clinica', 'SELECT 1 FROM core.budgets', 5);
SELECT pg_temp.expect_rows('T98 recepcao le os itens da clinica', 'SELECT 1 FROM core.budget_items', 2);
SELECT pg_temp.expect_err('T98 recepcao nao cria orcamento', '42501', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_r'));
SELECT pg_temp.expect_rows('T98 recepcao nao altera orcamento (0 linhas)', format('UPDATE core.budgets SET notes = ''x'' WHERE id = %L', :'b4'), 0);
SELECT pg_temp.expect_err('T98 recepcao nao insere item', '42501', pg_temp.item_sql(:'ca', :'b4', :'pt1', '1', '1'));
SELECT pg_temp.expect_rows('T98 recepcao nao altera item (0 linhas)', format('UPDATE core.budget_items SET quantity = 5 WHERE budget_id = %L', :'b1'), 0);
SELECT pg_temp.expect_rows('T98 recepcao nao apaga item (0 linhas)', format('DELETE FROM core.budget_items WHERE budget_id = %L', :'b1'), 0);
:ctx_a_d \gset
SELECT pg_temp.expect_err('T98 autoria: created_by diferente de app.user_id', '42501', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_r'));
SELECT pg_temp.expect_err('T98 autoria: usuario de outra clinica', '42501', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ub_d'));
:ctx_a_norole \gset
SELECT pg_temp.expect_rows('T98 sem papel: 0 orcamentos', 'SELECT 1 FROM core.budgets', 0);
SELECT pg_temp.expect_rows('T98 sem papel: 0 itens', 'SELECT 1 FROM core.budget_items', 0);
SELECT pg_temp.expect_err('T98 sem papel: INSERT falha', '42501', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d'));
:ctx_a_d \gset
SELECT set_config('app.role', 'ADMIN', true) AS _ \gset
SELECT pg_temp.expect_rows('T98 papel ADMIN: 0 orcamentos', 'SELECT 1 FROM core.budgets', 0);
:ctx_none \gset
SELECT pg_temp.expect_rows('T98 sem sessao: 0 orcamentos', 'SELECT 1 FROM core.budgets', 0);
:ctx_a_d_nouser \gset
SELECT pg_temp.expect_err('T98 sem app.user_id o INSERT falha (autoria e auditoria)', '42501', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d'));

RESET SESSION AUTHORIZATION;
ROLLBACK;
\o
SELECT 'budgets: OK' AS resultado;
