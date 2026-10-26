-- =====================================================================
-- tests/installments.sql | Fase 3 / F04-installments
-- Testes 100 a 103 da spec global da Fase 3: parcelas só de orçamento APPROVED e nascendo OPEN (100); soma das parcelas ativas = total do
-- orçamento, conferida no COMMIT (101); imutáveis, vencimento e número (102); papéis (103).
-- Como o teste nunca faz COMMIT, a constraint trigger adiável é forçada com pg_temp.expect_ok_commit / expect_err_commit
-- (SET CONSTRAINTS ALL IMMEDIATE). INSERTs de parcela que deveriam FALHAR no guard usam expect_err (a falha vem antes do COMMIT).
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : CR009 regra da parcela; CR011 soma != total; CR003 paciente anonimizado; 23505 número repetido; 23514 CHECK/imutável;
--            23503 FK; 42501 papel ou autoria.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set bdraft 'f4000000-0000-4000-8000-000000000501'
\set bsent  'f4000000-0000-4000-8000-000000000502'
\set brej   'f4000000-0000-4000-8000-000000000503'
\set bok    'f4000000-0000-4000-8000-000000000504'
\set bok2   'f4000000-0000-4000-8000-000000000505'
\set banon  'f4000000-0000-4000-8000-000000000506'
\set i1 'f4000000-0000-4000-8000-000000000511'
\set i2 'f4000000-0000-4000-8000-000000000512'
\set i3 'f4000000-0000-4000-8000-000000000513'
\set i4 'f4000000-0000-4000-8000-000000000514'
\set i5 'f4000000-0000-4000-8000-000000000515'
\set i6 'f4000000-0000-4000-8000-000000000516'
\set py1 'f4000000-0000-4000-8000-000000000521'

CREATE FUNCTION pg_temp.q(p_sql text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN EXECUTE p_sql INTO v; RETURN v; END $$;
CREATE FUNCTION pg_temp.expect_eq(p_label text, p_actual text, p_expected text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF p_actual IS DISTINCT FROM p_expected THEN RAISE EXCEPTION '%: esperado %, obtido %', p_label, p_expected, p_actual; END IF;
END $$;
-- orçamento de 2 x pt1 (300,00) levado até o estado pedido, pelo dentista
CREATE FUNCTION pg_temp.mk_budget(p_id text, p_patient text, p_status text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE pg_temp.budget_sql(p_id, 'f1000000-0000-4000-8000-00000000000a', p_patient, 'f1000000-0000-4000-8000-0000000000a1');
  EXECUTE pg_temp.item_sql('f1000000-0000-4000-8000-00000000000a', p_id, 'f4000000-0000-4000-8000-0000000000c1', '2', '1');
  IF p_status <> 'DRAFT' THEN EXECUTE pg_temp.budget_status_sql(p_id, p_status); END IF;
END $$;

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.mk_budget(:'bdraft', :'pa', 'DRAFT');
SELECT pg_temp.mk_budget(:'bsent',  :'pa', 'SENT');
SELECT pg_temp.mk_budget(:'brej',   :'pa', 'REJECTED');
SELECT pg_temp.mk_budget(:'bok',    :'pa', 'APPROVED');
SELECT pg_temp.mk_budget(:'bok2',   :'pa', 'APPROVED');

-- ---------------------------------------------------------------------
-- T100: só de orçamento APPROVED, nasce OPEN
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T100 orcamento DRAFT', 'CR009', pg_temp.inst_sql(:'i1', :'ca', :'bdraft', :'pa', '1', '300', '2031-10-01', :'ua_d'));
SELECT pg_temp.expect_err('T100 orcamento SENT', 'CR009', pg_temp.inst_sql(:'i1', :'ca', :'bsent', :'pa', '1', '300', '2031-10-01', :'ua_d'));
SELECT pg_temp.expect_err('T100 orcamento REJECTED', 'CR009', pg_temp.inst_sql(:'i1', :'ca', :'brej', :'pa', '1', '300', '2031-10-01', :'ua_d'));
SELECT pg_temp.expect_err('T100 nasce PAID', 'CR009', format(
  'INSERT INTO core.installments (id, clinic_id, budget_id, patient_id, number, amount, due_date, status, created_by) VALUES (%L, %L, %L, %L, 1, 300, ''2031-10-01'', ''PAID'', %L)',
  :'i1', :'ca', :'bok', :'pa', :'ua_d'));
SELECT pg_temp.expect_err('T100 nasce CANCELED', 'CR009', format(
  'INSERT INTO core.installments (id, clinic_id, budget_id, patient_id, number, amount, due_date, status, created_by) VALUES (%L, %L, %L, %L, 1, 300, ''2031-10-01'', ''CANCELED'', %L)',
  :'i1', :'ca', :'bok', :'pa', :'ua_d'));
SELECT pg_temp.expect_err('T100 paciente diferente do orcamento (FK composta)', '23503', pg_temp.inst_sql(:'i1', :'ca', :'bok', :'pa2', '1', '300', '2031-10-01', :'ua_d'));
SELECT pg_temp.expect_err('T100 orcamento inexistente', '23503', pg_temp.inst_sql(:'i1', :'ca', gen_random_uuid()::text, :'pa', '1', '300', '2031-10-01', :'ua_d'));
SELECT pg_temp.expect_ok_commit('T100 parcela unica de 300 do orcamento APPROVED', ARRAY[pg_temp.inst_sql(:'i1', :'ca', :'bok2', :'pa', '1', '300', '2031-10-01', :'ua_d')]);
SELECT pg_temp.expect_eq('T100 nasce OPEN', pg_temp.q(format('SELECT status FROM core.installments WHERE id = %L', :'i1')), 'OPEN');
-- paciente anonimizado: o orçamento foi aprovado antes da anonimização, e a parcela nova é recusada
SELECT pg_temp.mk_budget(:'banon', :'pa2', 'APPROVED');
RESET SESSION AUTHORIZATION;
SELECT set_config('core.anonymizing', 'on', true) AS _ \gset
UPDATE core.patients SET active = false, deactivated_at = now(), anonymized_at = now() WHERE id = :'pa2';
SELECT set_config('core.anonymizing', 'off', true) AS _ \gset
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T100 paciente anonimizado', 'CR003', pg_temp.inst_sql(:'i6', :'ca', :'banon', :'pa2', '1', '300', '2031-10-01', :'ua_d'));

-- ---------------------------------------------------------------------
-- T101: soma = total (no COMMIT)
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err_commit('T101 soma menor que o total (200 de 300)', 'CR011', ARRAY[
  pg_temp.inst_sql(:'i2', :'ca', :'bok', :'pa', '1', '100', '2031-10-01', :'ua_d'),
  pg_temp.inst_sql(:'i3', :'ca', :'bok', :'pa', '2', '100', '2031-11-01', :'ua_d')]);
SELECT pg_temp.expect_err_commit('T101 soma maior que o total (400 de 300)', 'CR011', ARRAY[
  pg_temp.inst_sql(:'i2', :'ca', :'bok', :'pa', '1', '200', '2031-10-01', :'ua_d'),
  pg_temp.inst_sql(:'i3', :'ca', :'bok', :'pa', '2', '200', '2031-11-01', :'ua_d')]);
SELECT pg_temp.expect_ok_commit('T101 soma igual ao total (3 x 100)', ARRAY[
  pg_temp.inst_sql(:'i2', :'ca', :'bok', :'pa', '1', '100', '2031-10-01', :'ua_d'),
  pg_temp.inst_sql(:'i3', :'ca', :'bok', :'pa', '2', '100', '2031-11-01', :'ua_d'),
  pg_temp.inst_sql(:'i4', :'ca', :'bok', :'pa', '3', '100', '2031-12-01', :'ua_d')]);
SELECT pg_temp.expect_eq('T101 3 parcelas ativas', pg_temp.q(format('SELECT count(*)::text FROM core.installments WHERE budget_id = %L', :'bok')), '3');
SELECT pg_temp.expect_ok_commit('T101 centavos: 100 + 100 + 100 permanece valido apos um UPDATE de vencimento',
  ARRAY[format('UPDATE core.installments SET due_date = DATE ''2031-12-15'' WHERE id = %L', :'i4')]);
-- cancelar uma sem substituir quebra a igualdade
SELECT pg_temp.expect_err_commit('T101 cancelar uma parcela sem substituir', 'CR011', ARRAY[pg_temp.inst_status_sql(:'i4', 'CANCELED')]);
SELECT pg_temp.expect_eq('T101 a falha desfez o cancelamento', pg_temp.q(format('SELECT status FROM core.installments WHERE id = %L', :'i4')), 'OPEN');
-- refazer o plano na mesma transação: cancela as 3 e cria 2 de 150 reaproveitando os números 1 e 2
SELECT pg_temp.expect_ok_commit('T101 refazer o plano (cancela 3, cria 2 reaproveitando os numeros)', ARRAY[
  format('UPDATE core.installments SET status = ''CANCELED'' WHERE budget_id = %L', :'bok'),
  pg_temp.inst_sql(:'i5', :'ca', :'bok', :'pa', '1', '150', '2031-10-10', :'ua_d'),
  pg_temp.inst_sql(:'i6', :'ca', :'bok', :'pa', '2', '150', '2031-11-10', :'ua_d')]);
SELECT pg_temp.expect_eq('T101 parcelas ativas = 2', pg_temp.q(format('SELECT count(*)::text FROM core.installments WHERE budget_id = %L AND status <> ''CANCELED''', :'bok')), '2');
SELECT pg_temp.expect_eq('T101 parcelas no total (3 canceladas + 2)', pg_temp.q(format('SELECT count(*)::text FROM core.installments WHERE budget_id = %L', :'bok')), '5');
SELECT pg_temp.expect_eq('T101 soma das ativas = total', pg_temp.q(format('SELECT sum(amount)::text FROM core.installments WHERE budget_id = %L AND status <> ''CANCELED''', :'bok')), '300.00');
-- cancelar todas, sem substituir: zero parcelas ativas passa
SELECT pg_temp.expect_ok_commit('T101 zero parcelas ativas passa', ARRAY[format('UPDATE core.installments SET status = ''CANCELED'' WHERE budget_id = %L', :'bok')]);
-- valores
SELECT pg_temp.expect_err('T101 valor zero', '23514', pg_temp.inst_sql(:'i2', :'ca', :'bok', :'pa', '1', '0', '2031-10-01', :'ua_d'));
SELECT pg_temp.expect_err('T101 valor acima do teto', '23514', pg_temp.inst_sql(:'i2', :'ca', :'bok', :'pa', '1', '1000000', '2031-10-01', :'ua_d'));
SELECT pg_temp.expect_err('T101 vencimento anterior a 2000', '23514', pg_temp.inst_sql(:'i2', :'ca', :'bok', :'pa', '1', '300', '1999-12-31', :'ua_d'));

-- ---------------------------------------------------------------------
-- T102: imutáveis, vencimento e número (orçamento bok2 com a parcela i1 de 300)
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T102 amount imutavel', '23514', format('UPDATE core.installments SET amount = 299 WHERE id = %L', :'i1'));
SELECT pg_temp.expect_err('T102 number imutavel', '23514', format('UPDATE core.installments SET number = 2 WHERE id = %L', :'i1'));
SELECT pg_temp.expect_err('T102 budget_id imutavel', '23514', format('UPDATE core.installments SET budget_id = %L WHERE id = %L', :'bok', :'i1'));
SELECT pg_temp.expect_err('T102 patient_id imutavel', '23514', format('UPDATE core.installments SET patient_id = %L WHERE id = %L', :'pa2', :'i1'));
SELECT pg_temp.expect_err('T102 created_by imutavel', '23514', format('UPDATE core.installments SET created_by = %L WHERE id = %L', :'ua_r', :'i1'));
SELECT pg_temp.expect_rows('T102 vencimento muda enquanto OPEN', format('UPDATE core.installments SET due_date = DATE ''2031-10-20'' WHERE id = %L', :'i1'), 1);
SELECT pg_temp.expect_err('T102 numero 0', '23514', pg_temp.inst_sql(:'i2', :'ca', :'bok', :'pa', '0', '300', '2031-10-01', :'ua_d'));
SELECT pg_temp.expect_err('T102 numero 61', '23514', pg_temp.inst_sql(:'i2', :'ca', :'bok', :'pa', '61', '300', '2031-10-01', :'ua_d'));
-- número repetido entre as ativas (bok2 já tem a parcela 1)
SELECT pg_temp.expect_err('T102 numero repetido entre as ativas', '23505', pg_temp.inst_sql(:'i2', :'ca', :'bok2', :'pa', '1', '100', '2031-10-01', :'ua_d'));
-- paga e cancelada: o vencimento congela
SELECT pg_temp.expect_rows('T102 quita i1 (pagamento de 300)', pg_temp.pay_sql(:'py1', :'ca', :'i1', 'PAYMENT', '300', '2031-10-02', '', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T102 i1 virou PAID', pg_temp.q(format('SELECT status FROM core.installments WHERE id = %L', :'i1')), 'PAID');
SELECT pg_temp.expect_err('T102 vencimento de parcela PAID', 'CR009', format('UPDATE core.installments SET due_date = DATE ''2031-10-25'' WHERE id = %L', :'i1'));
SELECT pg_temp.expect_err('T102 vencimento de parcela cancelada (i3)', 'CR009', format('UPDATE core.installments SET due_date = DATE ''2031-10-25'' WHERE id = %L', :'i3'));
SELECT pg_temp.expect_err('T102 cancelada nao reabre', 'CR009', pg_temp.inst_status_sql(:'i3', 'OPEN'));

-- ---------------------------------------------------------------------
-- T103: papéis
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_rows('T103 dentista le as parcelas da clinica (i1 + 5 do T101)', 'SELECT 1 FROM core.installments', 6);
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T103 recepcao ve 0 parcelas', 'SELECT 1 FROM core.installments', 0);
SELECT pg_temp.expect_err('T103 recepcao nao cria parcela', '42501', pg_temp.inst_sql(:'i2', :'ca', :'bok2', :'pa', '2', '100', '2031-10-01', :'ua_r'));
SELECT pg_temp.expect_rows('T103 recepcao nao altera (0 linhas)', format('UPDATE core.installments SET due_date = DATE ''2031-11-20'' WHERE id = %L', :'i5'), 0);
SELECT pg_temp.expect_err('T103 crm_app nao tem DELETE', '42501', format('DELETE FROM core.installments WHERE id = %L', :'i5'));
:ctx_a_d \gset
SELECT pg_temp.expect_err('T103 autoria: created_by diferente de app.user_id', '42501', pg_temp.inst_sql(:'i2', :'ca', :'bok2', :'pa', '2', '100', '2031-10-01', :'ua_r'));
SELECT pg_temp.expect_err('T103 dentista tambem nao apaga', '42501', format('DELETE FROM core.installments WHERE id = %L', :'i5'));
:ctx_a_norole \gset
SELECT pg_temp.expect_rows('T103 sem papel: 0 parcelas', 'SELECT 1 FROM core.installments', 0);
:ctx_a_d \gset
SELECT set_config('app.role', 'ADMIN', true) AS _ \gset
SELECT pg_temp.expect_rows('T103 papel ADMIN: 0 parcelas', 'SELECT 1 FROM core.installments', 0);
:ctx_none \gset
SELECT pg_temp.expect_rows('T103 sem sessao: 0 parcelas', 'SELECT 1 FROM core.installments', 0);

RESET SESSION AUTHORIZATION;
ROLLBACK;
\o
SELECT 'installments: OK' AS resultado;
