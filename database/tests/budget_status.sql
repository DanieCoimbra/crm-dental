-- =====================================================================
-- tests/budget_status.sql | Fase 3 / F02-budgets
-- Testes 95 (congelamento) e 96 (status) da spec global da Fase 3: depois de DRAFT o conteúdo e os itens não mudam (CR007);
-- APPROVED e REJECTED são terminais, ninguém volta a DRAFT (CR008); enviar/aprovar exige itens e total > 0; DRAFT -> APPROVED/REJECTED
-- direto é permitido (decisão S6); sent_at, decided_at e total_amount são do banco (o valor do cliente é ignorado).
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : CR007 orçamento congelado; CR008 status inválido; 23514 coluna imutável.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set e1 'f4000000-0000-4000-8000-000000000401'
\set e2 'f4000000-0000-4000-8000-000000000402'
\set e3 'f4000000-0000-4000-8000-000000000403'
\set s1 'f4000000-0000-4000-8000-000000000411'
\set s2 'f4000000-0000-4000-8000-000000000412'
\set s3 'f4000000-0000-4000-8000-000000000413'
\set s4 'f4000000-0000-4000-8000-000000000414'
\set s5 'f4000000-0000-4000-8000-000000000415'
\set s6 'f4000000-0000-4000-8000-000000000416'
\set ap_a 'f4000000-0000-4000-8000-000000000421'

:ctx_a_d \gset
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by)
  VALUES (:'ap_a', :'ca', :'pa', '2031-09-01 08:00+00', '2031-09-01 08:30+00', 'SCHEDULED', :'ua_d');

CREATE FUNCTION pg_temp.q(p_sql text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN EXECUTE p_sql INTO v; RETURN v; END $$;
CREATE FUNCTION pg_temp.f(p_id text, p_field text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN EXECUTE format('SELECT %I::text FROM core.budgets WHERE id = %L', p_field, p_id) INTO v; RETURN v; END $$;
CREATE FUNCTION pg_temp.expect_eq(p_label text, p_actual text, p_expected text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF p_actual IS DISTINCT FROM p_expected THEN RAISE EXCEPTION '%: esperado %, obtido %', p_label, p_expected, p_actual; END IF;
END $$;
CREATE FUNCTION pg_temp.expect_true(p_label text, p_sql text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v boolean;
BEGIN EXECUTE p_sql INTO v; IF v IS NOT TRUE THEN RAISE EXCEPTION '%: condicao falsa (%)', p_label, p_sql; END IF; END $$;

-- orçamento com 1 item (2 x pt1 = 300,00): e1 (vira SENT), e2 (aprovado direto), e3 (recusado), s1..s6 variações
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('cria e1', pg_temp.budget_sql(:'e1', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('item e1', pg_temp.item_sql(:'ca', :'e1', :'pt1', '2', '1'), 1);

-- ---------------------------------------------------------------------
-- T96: status
-- ---------------------------------------------------------------------
-- sem itens: enviar e aprovar falham; recusar (descartar o rascunho) passa
SELECT pg_temp.expect_rows('cria s1 (vazio)', pg_temp.budget_sql(:'s1', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_err('T96 enviar sem itens', 'CR008', pg_temp.budget_status_sql(:'s1', 'SENT'));
SELECT pg_temp.expect_err('T96 aprovar sem itens', 'CR008', pg_temp.budget_status_sql(:'s1', 'APPROVED'));
SELECT pg_temp.expect_rows('T96 descartar o rascunho vazio (REJECTED)', pg_temp.budget_status_sql(:'s1', 'REJECTED'), 1);
SELECT pg_temp.expect_true('T96 descartado traz decided_at', format('SELECT decided_at IS NOT NULL AND sent_at IS NULL FROM core.budgets WHERE id = %L', :'s1'));
-- total zero (item com desconto igual a linha): enviar falha
SELECT pg_temp.expect_rows('cria s2', pg_temp.budget_sql(:'s2', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('item s2 de linha zero', pg_temp.item_sql(:'ca', :'s2', :'pt1', '1', '1', '150'), 1);
SELECT pg_temp.expect_err('T96 enviar com total zero', 'CR008', pg_temp.budget_status_sql(:'s2', 'SENT'));
SELECT pg_temp.expect_err('T96 aprovar com total zero', 'CR008', pg_temp.budget_status_sql(:'s2', 'APPROVED'));

-- enviar: carimbo do banco; o valor do cliente (sent_at, total_amount) é ignorado
SELECT pg_temp.expect_rows('T96 enviar e1 com sent_at e total forjados', format(
  'UPDATE core.budgets SET status = ''SENT'', sent_at = TIMESTAMPTZ ''2000-01-01 00:00+00'', total_amount = 5 WHERE id = %L', :'e1'), 1);
SELECT pg_temp.expect_eq('T96 status SENT', pg_temp.f(:'e1', 'status'), 'SENT');
SELECT pg_temp.expect_true('T96 sent_at e do banco (agora), nao o forjado', format('SELECT sent_at > now() - interval ''1 minute'' FROM core.budgets WHERE id = %L', :'e1'));
SELECT pg_temp.expect_eq('T96 total mantido em 300', pg_temp.f(:'e1', 'total_amount'), '300.00');
SELECT pg_temp.expect_true('T96 decided_at nulo', format('SELECT decided_at IS NULL FROM core.budgets WHERE id = %L', :'e1'));
-- reenviar (UPDATE sem mudar nada) não mexe nos carimbos nem no total
SELECT pg_temp.expect_rows('T96 SENT -> SENT (reenvio) e no-op', format('UPDATE core.budgets SET status = ''SENT'', total_amount = 1, sent_at = now() + interval ''1 day'' WHERE id = %L', :'e1'), 1);
SELECT pg_temp.expect_true('T96 carimbo e total intactos', format('SELECT sent_at < now() + interval ''1 hour'' AND total_amount = 300 FROM core.budgets WHERE id = %L', :'e1'));
-- não volta
SELECT pg_temp.expect_err('T96 SENT -> DRAFT', 'CR008', pg_temp.budget_status_sql(:'e1', 'DRAFT'));
-- decidir
SELECT pg_temp.expect_rows('T96 SENT -> APPROVED', pg_temp.budget_status_sql(:'e1', 'APPROVED'), 1);
SELECT pg_temp.expect_true('T96 decided_at preenchido e sent_at <= decided_at', format('SELECT decided_at IS NOT NULL AND sent_at <= decided_at FROM core.budgets WHERE id = %L', :'e1'));
-- terminal
SELECT pg_temp.expect_err('T96 APPROVED -> REJECTED', 'CR008', pg_temp.budget_status_sql(:'e1', 'REJECTED'));
SELECT pg_temp.expect_err('T96 APPROVED -> SENT', 'CR008', pg_temp.budget_status_sql(:'e1', 'SENT'));
SELECT pg_temp.expect_err('T96 APPROVED -> DRAFT', 'CR008', pg_temp.budget_status_sql(:'e1', 'DRAFT'));

-- DRAFT -> APPROVED direto (S6) e DRAFT -> REJECTED direto
SELECT pg_temp.expect_rows('cria e2', pg_temp.budget_sql(:'e2', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('item e2', pg_temp.item_sql(:'ca', :'e2', :'pt2', '1', '1'), 1);
SELECT pg_temp.expect_rows('T96 DRAFT -> APPROVED direto (aprovacao verbal)', pg_temp.budget_status_sql(:'e2', 'APPROVED'), 1);
SELECT pg_temp.expect_true('T96 aprovado direto: decided_at e sent_at nulo', format('SELECT decided_at IS NOT NULL AND sent_at IS NULL FROM core.budgets WHERE id = %L', :'e2'));
SELECT pg_temp.expect_rows('cria e3', pg_temp.budget_sql(:'e3', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('item e3', pg_temp.item_sql(:'ca', :'e3', :'pt1', '1', '1'), 1);
SELECT pg_temp.expect_rows('T96 SENT em e3', pg_temp.budget_status_sql(:'e3', 'SENT'), 1);
SELECT pg_temp.expect_rows('T96 SENT -> REJECTED', pg_temp.budget_status_sql(:'e3', 'REJECTED'), 1);
SELECT pg_temp.expect_err('T96 REJECTED -> APPROVED', 'CR008', pg_temp.budget_status_sql(:'e3', 'APPROVED'));
SELECT pg_temp.expect_err('T96 REJECTED -> DRAFT', 'CR008', pg_temp.budget_status_sql(:'e3', 'DRAFT'));
SELECT pg_temp.expect_err('T96 REJECTED -> SENT', 'CR008', pg_temp.budget_status_sql(:'e3', 'SENT'));
-- mesma transação: DRAFT -> SENT -> APPROVED (sent_at <= decided_at mesmo com now() igual)
SELECT pg_temp.expect_rows('cria s3', pg_temp.budget_sql(:'s3', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('item s3', pg_temp.item_sql(:'ca', :'s3', :'pt1', '1', '1'), 1);
SELECT pg_temp.expect_rows('T96 s3 -> SENT', pg_temp.budget_status_sql(:'s3', 'SENT'), 1);
SELECT pg_temp.expect_rows('T96 s3 -> APPROVED na mesma transacao', pg_temp.budget_status_sql(:'s3', 'APPROVED'), 1);
SELECT pg_temp.expect_true('T96 sent_at <= decided_at', format('SELECT sent_at <= decided_at FROM core.budgets WHERE id = %L', :'s3'));

-- ---------------------------------------------------------------------
-- T95: congelamento (e1 = APPROVED, e3 = REJECTED, s4 = SENT)
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_rows('cria s4', pg_temp.budget_sql(:'s4', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('item s4', pg_temp.item_sql(:'ca', :'s4', :'pt1', '2', '1'), 1);
SELECT pg_temp.expect_rows('s4 -> SENT', pg_temp.budget_status_sql(:'s4', 'SENT'), 1);
SELECT pg_temp.expect_err('T95 SENT: item novo', 'CR007', pg_temp.item_sql(:'ca', :'s4', :'pt2', '1', '2'));
SELECT pg_temp.expect_err('T95 SENT: altera item', 'CR007', format('UPDATE core.budget_items SET quantity = 9 WHERE budget_id = %L', :'s4'));
SELECT pg_temp.expect_err('T95 SENT: remove item', 'CR007', format('DELETE FROM core.budget_items WHERE budget_id = %L', :'s4'));
SELECT pg_temp.expect_err('T95 SENT: desconto', 'CR007', pg_temp.budget_discount_sql(:'s4', '10'));
SELECT pg_temp.expect_err('T95 SENT: notes', 'CR007', format('UPDATE core.budgets SET notes = ''x'' WHERE id = %L', :'s4'));
SELECT pg_temp.expect_err('T95 SENT: valid_until', 'CR007', format('UPDATE core.budgets SET valid_until = DATE ''2031-12-31'' WHERE id = %L', :'s4'));
SELECT pg_temp.expect_err('T95 SENT: appointment_id', 'CR007', format('UPDATE core.budgets SET appointment_id = %L WHERE id = %L', :'ap_a', :'s4'));
SELECT pg_temp.expect_err('T95 SENT: patient_id e imutavel (23514)', '23514', format('UPDATE core.budgets SET patient_id = %L WHERE id = %L', :'pa2', :'s4'));
SELECT pg_temp.expect_err('T95 APPROVED: item novo', 'CR007', pg_temp.item_sql(:'ca', :'e1', :'pt2', '1', '2'));
SELECT pg_temp.expect_err('T95 APPROVED: altera item', 'CR007', format('UPDATE core.budget_items SET quantity = 9 WHERE budget_id = %L', :'e1'));
SELECT pg_temp.expect_err('T95 APPROVED: remove item', 'CR007', format('DELETE FROM core.budget_items WHERE budget_id = %L', :'e1'));
SELECT pg_temp.expect_err('T95 APPROVED: desconto', 'CR007', pg_temp.budget_discount_sql(:'e1', '10'));
SELECT pg_temp.expect_err('T95 APPROVED: notes', 'CR007', format('UPDATE core.budgets SET notes = ''x'' WHERE id = %L', :'e1'));
SELECT pg_temp.expect_err('T95 REJECTED: item novo', 'CR007', pg_temp.item_sql(:'ca', :'e3', :'pt2', '1', '2'));
SELECT pg_temp.expect_err('T95 REJECTED: notes', 'CR007', format('UPDATE core.budgets SET notes = ''x'' WHERE id = %L', :'e3'));
SELECT pg_temp.expect_eq('T95 totais intactos depois das tentativas', pg_temp.f(:'s4', 'total_amount'), '300.00');
-- a flag da anonimização é um GUC forjável: com ela ligada por uma sessão comum (crm_app), o congelamento continua valendo
SELECT pg_temp.expect_rows('cria s5 (com notes)', pg_temp.budget_sql(:'s5', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('s5 notes em DRAFT', format('UPDATE core.budgets SET notes = ''Joao da Silva'' WHERE id = %L', :'s5'), 1);
SELECT pg_temp.expect_rows('item s5', pg_temp.item_sql(:'ca', :'s5', :'pt1', '1', '1'), 1);
SELECT pg_temp.expect_rows('s5 -> SENT', pg_temp.budget_status_sql(:'s5', 'SENT'), 1);
SELECT pg_temp.expect_rows('cria s6 (com notes)', pg_temp.budget_sql(:'s6', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('s6 notes em DRAFT', format('UPDATE core.budgets SET notes = ''Maria'' WHERE id = %L', :'s6'), 1);
SELECT pg_temp.expect_rows('item s6', pg_temp.item_sql(:'ca', :'s6', :'pt1', '1', '1'), 1);
SELECT pg_temp.expect_rows('s6 -> APPROVED', pg_temp.budget_status_sql(:'s6', 'APPROVED'), 1);
SELECT set_config('core.anonymizing', 'on', true) AS _ \gset
SELECT pg_temp.expect_err('T95 flag forjada: notes de orcamento APPROVED continuam congeladas', 'CR007', format('UPDATE core.budgets SET notes = NULL WHERE id = %L', :'s6'));
SELECT pg_temp.expect_err('T95 flag forjada: notes de orcamento SENT continuam congeladas', 'CR007', format('UPDATE core.budgets SET notes = NULL WHERE id = %L', :'s5'));
SELECT pg_temp.expect_err('T95 flag forjada: desconto continua congelado', 'CR007', pg_temp.budget_discount_sql(:'s5', '10'));
SELECT pg_temp.expect_err('T95 flag forjada: status continua terminal', 'CR008', pg_temp.budget_status_sql(:'s6', 'REJECTED'));
SELECT set_config('core.anonymizing', 'off', true) AS _ \gset
SELECT pg_temp.expect_eq('T95 as notes ficaram intactas', pg_temp.q(format('SELECT notes FROM core.budgets WHERE id = %L', :'s6')), 'Maria');
-- um UPDATE que reenvia as mesmas colunas (como o JPA) passa
SELECT pg_temp.expect_rows('T95 UPDATE sem mudanca de conteudo passa (o JPA reenvia tudo)', format(
  'UPDATE core.budgets SET discount_amount = discount_amount, notes = notes, valid_until = valid_until, appointment_id = appointment_id WHERE id = %L', :'s4'), 1);

RESET SESSION AUTHORIZATION;
ROLLBACK;
\o
SELECT 'budget_status: OK' AS resultado;
