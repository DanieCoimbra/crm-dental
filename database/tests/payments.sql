-- =====================================================================
-- tests/payments.sql | Fase 3 / F05-payments
-- Testes 104, 105 e 108 da spec global da Fase 3: pagamento parcial e total, status automático da parcela (com version + 1), estorno
-- (mesmo valor, mesma parcela, um por pagamento, nunca de estorno), saldo (CR010), parcela cancelada, status manual incoerente (CR009),
-- paciente anonimizado (CR003) e inativo (passa), papéis. Concorrência (107) em commercial_concurrency.sh; append-only (106) em
-- payments_append_only.sql.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : CR010 saldo/parcela cancelada/estorno inválido; CR009 status incoerente; CR003 paciente anonimizado; 23505 estorno repetido;
--            23503 FK; 23514 CHECK; 42501 papel ou autoria.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set bA 'f4000000-0000-4000-8000-000000000601'
\set iA 'f4000000-0000-4000-8000-000000000602'
\set pA 'f4000000-0000-4000-8000-000000000603'
\set bA2 'f4000000-0000-4000-8000-000000000611'
\set iA2 'f4000000-0000-4000-8000-000000000612'
\set pA2 'f4000000-0000-4000-8000-000000000613'
\set bA3 'f4000000-0000-4000-8000-000000000621'
\set iA3 'f4000000-0000-4000-8000-000000000622'
\set pA3 'f4000000-0000-4000-8000-000000000623'
\set bA4 'f4000000-0000-4000-8000-000000000631'
\set iA4 'f4000000-0000-4000-8000-000000000632'
\set pA4 'f4000000-0000-4000-8000-000000000633'
\set bA5 'f4000000-0000-4000-8000-000000000641'
\set iA5 'f4000000-0000-4000-8000-000000000642'
\set pA5 'f4000000-0000-4000-8000-000000000643'
\set bB 'f4000000-0000-4000-8000-0000000006b1'
\set iB 'f4000000-0000-4000-8000-0000000006b2'
\set pB 'f4000000-0000-4000-8000-0000000006b3'
\set x1 'f4000000-0000-4000-8000-000000000651'
\set x2 'f4000000-0000-4000-8000-000000000652'
\set x3 'f4000000-0000-4000-8000-000000000653'
\set x4 'f4000000-0000-4000-8000-000000000654'
\set x5 'f4000000-0000-4000-8000-000000000655'
\set x6 'f4000000-0000-4000-8000-000000000656'
\set x7 'f4000000-0000-4000-8000-000000000657'
\set x8 'f4000000-0000-4000-8000-000000000658'

CREATE FUNCTION pg_temp.q(p_sql text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN EXECUTE p_sql INTO v; RETURN v; END $$;
CREATE FUNCTION pg_temp.expect_eq(p_label text, p_actual text, p_expected text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF p_actual IS DISTINCT FROM p_expected THEN RAISE EXCEPTION '%: esperado %, obtido %', p_label, p_expected, p_actual; END IF;
END $$;
CREATE FUNCTION pg_temp.inst(p_id text, p_field text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN EXECUTE format('SELECT %I::text FROM core.installments WHERE id = %L', p_field, p_id) INTO v; RETURN v; END $$;

-- fixtures como administrador (cada uma: orçamento APPROVED de 300, parcela única de 300, pagamento parcial de 100)
:ctx_a_d \gset
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'pa',  :'pt1', :'bA',  :'iA',  :'pA');
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'pa',  :'pt1', :'bA2', :'iA2', :'pA2');
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'pa',  :'pt1', :'bA3', :'iA3', :'pA3');
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'pa2', :'pt1', :'bA4', :'iA4', :'pA4');
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'pa',  :'pt1', :'bA5', :'iA5', :'pA5');
:ctx_b_d \gset
SELECT pg_temp.f3_fixture(:'cb', :'ub_d', :'pb', :'ptb', :'bB', :'iB', :'pB');
:ctx_a_d \gset

SET SESSION AUTHORIZATION crm_app;

-- ---------------------------------------------------------------------
-- T104: pagamento parcial, quitação, estorno
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_eq('T104 iA comeca OPEN com 100 pagos', pg_temp.inst(:'iA', 'status'), 'OPEN');
SELECT pg_temp.expect_eq('T104 version inicial da parcela', pg_temp.inst(:'iA', 'version'), '0');
SELECT pg_temp.expect_rows('T104 segundo pagamento parcial (100)', pg_temp.pay_sql(:'x1', :'ca', :'iA', 'PAYMENT', '100', '2031-07-12', '', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T104 net 200: continua OPEN', pg_temp.inst(:'iA', 'status'), 'OPEN');
SELECT pg_temp.expect_eq('T104 sem mudanca de status, version intacta', pg_temp.inst(:'iA', 'version'), '0');
SELECT pg_temp.expect_rows('T104 pagamento do restante (100)', pg_temp.pay_sql(:'x2', :'ca', :'iA', 'PAYMENT', '100', '2031-07-13', '', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T104 net 300: PAID', pg_temp.inst(:'iA', 'status'), 'PAID');
SELECT pg_temp.expect_eq('T104 version +1 na mudanca de status', pg_temp.inst(:'iA', 'version'), '1');
-- estorno: mesmo valor, aponta o pagamento; a parcela reabre
SELECT pg_temp.expect_err('T104 estorno de valor diferente', 'CR010', pg_temp.pay_sql(:'x3', :'ca', :'iA', 'REVERSAL', '50', '2031-07-14', :'x2', :'ua_d'));
SELECT pg_temp.expect_err('T104 estorno de pagamento de OUTRA parcela (FK composta)', '23503', pg_temp.pay_sql(:'x3', :'ca', :'iA', 'REVERSAL', '100', '2031-07-14', :'pA2', :'ua_d'));
SELECT pg_temp.expect_err('T104 estorno de pagamento inexistente', '23503', pg_temp.pay_sql(:'x3', :'ca', :'iA', 'REVERSAL', '100', '2031-07-14', gen_random_uuid()::text, :'ua_d'));
SELECT pg_temp.expect_rows('T104 estorno do pagamento x2', pg_temp.pay_sql(:'x3', :'ca', :'iA', 'REVERSAL', '100', '2031-07-14', :'x2', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T104 net 200: a parcela voltou a OPEN', pg_temp.inst(:'iA', 'status'), 'OPEN');
SELECT pg_temp.expect_eq('T104 version +1 de novo', pg_temp.inst(:'iA', 'version'), '2');
SELECT pg_temp.expect_err('T104 estorno repetido do mesmo pagamento', '23505', pg_temp.pay_sql(:'x4', :'ca', :'iA', 'REVERSAL', '100', '2031-07-14', :'x2', :'ua_d'));
SELECT pg_temp.expect_err('T104 estorno de estorno', 'CR010', pg_temp.pay_sql(:'x4', :'ca', :'iA', 'REVERSAL', '100', '2031-07-15', :'x3', :'ua_d'));
SELECT pg_temp.expect_err('T104 estorno sem apontar o pagamento', '23514', pg_temp.pay_sql(:'x3', :'ca', :'iA', 'REVERSAL', '100', '2031-07-14', '', :'ua_d'));
SELECT pg_temp.expect_err('T104 PAYMENT apontando outro pagamento', '23514', pg_temp.pay_sql(:'x3', :'ca', :'iA', 'PAYMENT', '100', '2031-07-14', :'x2', :'ua_d'));
-- CHECKs
SELECT pg_temp.expect_err('T104 valor zero', '23514', pg_temp.pay_sql(:'x4', :'ca', :'iA', 'PAYMENT', '0', '2031-07-15', '', :'ua_d'));
SELECT pg_temp.expect_err('T104 valor acima do teto (o saldo reprova antes do CHECK)', 'CR010', pg_temp.pay_sql(:'x4', :'ca', :'iA', 'PAYMENT', '1000000', '2031-07-15', '', :'ua_d'));
SELECT pg_temp.expect_err('T104 data anterior a 2000', '23514', pg_temp.pay_sql(:'x4', :'ca', :'iA', 'PAYMENT', '10', '1999-12-31', '', :'ua_d'));
SELECT pg_temp.expect_err('T104 forma de pagamento fora da lista', '23514', format(
  'INSERT INTO core.payments (clinic_id, installment_id, kind, amount, method, paid_on, created_by) VALUES (%L, %L, ''PAYMENT'', 10, ''FIADO'', ''2031-07-15'', %L)', :'ca', :'iA', :'ua_d'));
SELECT pg_temp.expect_err('T104 tipo fora da lista', '23514', format(
  'INSERT INTO core.payments (clinic_id, installment_id, kind, amount, method, paid_on, created_by) VALUES (%L, %L, ''REFUND'', 10, ''PIX'', ''2031-07-15'', %L)', :'ca', :'iA', :'ua_d'));
SELECT pg_temp.expect_err('T104 parcela de outra clinica (RLS)', '42501', pg_temp.pay_sql(:'x4', :'cb', :'iB', 'PAYMENT', '10', '2031-07-15', '', :'ua_d'));
SELECT pg_temp.expect_err('T104 parcela inexistente', '23503', pg_temp.pay_sql(:'x4', :'ca', gen_random_uuid()::text, 'PAYMENT', '10', '2031-07-15', '', :'ua_d'));

-- ---------------------------------------------------------------------
-- T105: saldo, parcela cancelada, status manual incoerente
-- ---------------------------------------------------------------------
-- iA: net 200, saldo 100
SELECT pg_temp.expect_err('T105 pagamento maior que o saldo (100,01)', 'CR010', pg_temp.pay_sql(:'x4', :'ca', :'iA', 'PAYMENT', '100.01', '2031-07-16', '', :'ua_d'));
SELECT pg_temp.expect_rows('T105 pagamento exato do saldo (100)', pg_temp.pay_sql(:'x4', :'ca', :'iA', 'PAYMENT', '100', '2031-07-16', '', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T105 quitada', pg_temp.inst(:'iA', 'status'), 'PAID');
SELECT pg_temp.expect_err('T105 qualquer pagamento em parcela quitada', 'CR010', pg_temp.pay_sql(:'x5', :'ca', :'iA', 'PAYMENT', '0.01', '2031-07-17', '', :'ua_d'));
SELECT pg_temp.expect_err('T105 status OPEN manual com liquido cheio', 'CR009', pg_temp.inst_status_sql(:'iA', 'OPEN'));
SELECT pg_temp.expect_err('T105 cancelar parcela quitada', 'CR009', pg_temp.inst_status_sql(:'iA', 'CANCELED'));
-- iA2: net 100, saldo 200
SELECT pg_temp.expect_err('T105 status PAID manual sem liquido suficiente', 'CR009', pg_temp.inst_status_sql(:'iA2', 'PAID'));
SELECT pg_temp.expect_err('T105 cancelar parcela com saldo pago', 'CR009', pg_temp.inst_status_sql(:'iA2', 'CANCELED'));
SELECT pg_temp.expect_rows('T105 pagamento de 200 quita iA2', pg_temp.pay_sql(:'x5', :'ca', :'iA2', 'PAYMENT', '200', '2031-07-17', '', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T105 version de iA2 +1', pg_temp.inst(:'iA2', 'version'), '1');
-- iA3: estorna o pagamento de 100 (net 0) e cancela; depois não recebe pagamento
SELECT pg_temp.expect_rows('T105 estorna o pagamento de iA3', pg_temp.pay_sql(:'x6', :'ca', :'iA3', 'REVERSAL', '100', '2031-07-18', :'pA3', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T105 iA3 segue OPEN (net 0)', pg_temp.inst(:'iA3', 'status'), 'OPEN');
SELECT pg_temp.expect_ok_commit('T105 estorna, depois cancela (liquido zero)', ARRAY[pg_temp.inst_status_sql(:'iA3', 'CANCELED')]);
SELECT pg_temp.expect_eq('T105 iA3 CANCELED', pg_temp.inst(:'iA3', 'status'), 'CANCELED');
SELECT pg_temp.expect_err('T105 pagamento em parcela cancelada', 'CR010', pg_temp.pay_sql(:'x7', :'ca', :'iA3', 'PAYMENT', '10', '2031-07-19', '', :'ua_d'));
SELECT pg_temp.expect_err('T105 parcela cancelada nao muda de status', 'CR009', pg_temp.inst_status_sql(:'iA3', 'OPEN'));

-- ---------------------------------------------------------------------
-- T108: paciente anonimizado (CR003) e inativo (passa)
-- ---------------------------------------------------------------------
RESET SESSION AUTHORIZATION;
SELECT set_config('core.anonymizing', 'on', true) AS _ \gset
UPDATE core.patients SET active = false, deactivated_at = now(), anonymized_at = now() WHERE id = :'pa2';
SELECT set_config('core.anonymizing', 'off', true) AS _ \gset
UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = :'pa';
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T108 pagamento de paciente anonimizado', 'CR003', pg_temp.pay_sql(:'x7', :'ca', :'iA4', 'PAYMENT', '10', '2031-07-19', '', :'ua_d'));
SELECT pg_temp.expect_err('T108 estorno de paciente anonimizado', 'CR003', pg_temp.pay_sql(:'x7', :'ca', :'iA4', 'REVERSAL', '100', '2031-07-19', :'pA4', :'ua_d'));
SELECT pg_temp.expect_rows('T108 paciente INATIVO ainda paga (a divida continua)', pg_temp.pay_sql(:'x8', :'ca', :'iA5', 'PAYMENT', '50', '2031-07-19', '', :'ua_d'), 1);
SELECT pg_temp.expect_eq('T108 iA5 net 150 segue OPEN', pg_temp.inst(:'iA5', 'status'), 'OPEN');

-- ---------------------------------------------------------------------
-- Papéis (T103 parte payments)
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_rows('T103 dentista le os pagamentos da clinica', 'SELECT 1 FROM core.payments', 12);
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T103 recepcao ve 0 pagamentos', 'SELECT 1 FROM core.payments', 0);
SELECT pg_temp.expect_err('T103 recepcao nao registra pagamento', '42501', pg_temp.pay_sql(gen_random_uuid()::text, :'ca', :'iA5', 'PAYMENT', '10', '2031-07-20', '', :'ua_r'));
:ctx_a_d \gset
SELECT pg_temp.expect_err('T103 autoria: created_by diferente de app.user_id', '42501', pg_temp.pay_sql(gen_random_uuid()::text, :'ca', :'iA5', 'PAYMENT', '10', '2031-07-20', '', :'ua_r'));
:ctx_a_norole \gset
SELECT pg_temp.expect_rows('T103 sem papel: 0 pagamentos', 'SELECT 1 FROM core.payments', 0);
SELECT pg_temp.expect_err('T103 sem papel: INSERT falha', '42501', pg_temp.pay_sql(gen_random_uuid()::text, :'ca', :'iA5', 'PAYMENT', '10', '2031-07-20', '', :'ua_d'));
:ctx_none \gset
SELECT pg_temp.expect_rows('T103 sem sessao: 0 pagamentos', 'SELECT 1 FROM core.payments', 0);

RESET SESSION AUTHORIZATION;
ROLLBACK;
\o
SELECT 'payments: OK' AS resultado;
