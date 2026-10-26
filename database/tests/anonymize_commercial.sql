-- =====================================================================
-- tests/anonymize_commercial.sql | Fase 3 / F07-commercial-anonymization
-- Testes 112 e 113 da spec global da Fase 3: a anonimização de paciente (V013, V021, ajustada na V029) recusa com CR006 quando há parcela
-- OPEN, zera budgets.notes, marca os PDFs do orçamento como removidos (deleted_at) e MANTÉM orçamentos, itens, parcelas e pagamentos;
-- a precedência é CR002 > CR004 > CR006; orçamento DRAFT/SENT sem parcelas não bloqueia; só o tenant da sessão é alcançado.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : CR006 parcela em aberto; CR004 agendamento futuro; CR002 retenção não vencida; CR003 paciente anonimizado.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set r_open  'f4000000-0000-4000-8000-000000000a01'
\set r_paid  'f4000000-0000-4000-8000-000000000a02'
\set r_canc  'f4000000-0000-4000-8000-000000000a03'
\set r_sent  'f4000000-0000-4000-8000-000000000a04'
\set r_draft 'f4000000-0000-4000-8000-000000000a05'
\set r_ret   'f4000000-0000-4000-8000-000000000a06'
\set r_appt  'f4000000-0000-4000-8000-000000000a07'
\set r_b     'f4000000-0000-4000-8000-000000000a08'

\set bo 'f4000000-0000-4000-8000-000000000b01'
\set io 'f4000000-0000-4000-8000-000000000b02'
\set po 'f4000000-0000-4000-8000-000000000b03'
\set bp 'f4000000-0000-4000-8000-000000000b11'
\set ip 'f4000000-0000-4000-8000-000000000b12'
\set pp 'f4000000-0000-4000-8000-000000000b13'
\set bc 'f4000000-0000-4000-8000-000000000b21'
\set ic 'f4000000-0000-4000-8000-000000000b22'
\set pc 'f4000000-0000-4000-8000-000000000b23'
\set bs 'f4000000-0000-4000-8000-000000000b31'
\set bd 'f4000000-0000-4000-8000-000000000b41'
\set br 'f4000000-0000-4000-8000-000000000b51'
\set ir 'f4000000-0000-4000-8000-000000000b52'
\set pr 'f4000000-0000-4000-8000-000000000b53'
\set ba 'f4000000-0000-4000-8000-000000000b61'
\set ia 'f4000000-0000-4000-8000-000000000b62'
\set pa_ 'f4000000-0000-4000-8000-000000000b63'
\set bb 'f4000000-0000-4000-8000-000000000b71'
\set ib 'f4000000-0000-4000-8000-000000000b72'
\set pb_ 'f4000000-0000-4000-8000-000000000b73'
\set rev 'f4000000-0000-4000-8000-000000000b81'

CREATE FUNCTION pg_temp.anon_sql(p_patient text) RETURNS text LANGUAGE sql AS $$
  SELECT 'SELECT core.anonymize_patient(' || quote_literal(p_patient) || ')' $$;
CREATE FUNCTION pg_temp.q(p_sql text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN EXECUTE p_sql INTO v; RETURN v; END $$;
CREATE FUNCTION pg_temp.expect_eq(p_label text, p_actual text, p_expected text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF p_actual IS DISTINCT FROM p_expected THEN RAISE EXCEPTION '%: esperado %, obtido %', p_label, p_expected, p_actual; END IF;
END $$;
-- orçamento com UM item (2 x pt1) e notes, no estado pedido (DRAFT, SENT), como administrador
CREATE FUNCTION pg_temp.mk_budget_notes(p_clinic uuid, p_user uuid, p_patient uuid, p_budget uuid, p_proc uuid, p_status text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO core.budgets (id, clinic_id, patient_id, notes, created_by) VALUES (p_budget, p_clinic, p_patient, 'Joao da Silva, desconto combinado', p_user);
  INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position) VALUES (p_clinic, p_budget, p_proc, 2, 1);
  IF p_status <> 'DRAFT' THEN UPDATE core.budgets SET status = p_status WHERE id = p_budget; END IF;
END $$;

-- ---------------------------------------------------------------------
-- Fixtures como administrador (clínica A); pacientes criados ATIVOS e desativados no fim
-- ---------------------------------------------------------------------
:ctx_a_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, active, deactivated_at, created_by) VALUES
  (:'r_open',  :'ca', 'Parcela Aberta',      '1980-01-01', '11999990021', true, NULL, :'ua_d'),
  (:'r_paid',  :'ca', 'Tudo Pago',           '1980-01-02', '11999990022', true, NULL, :'ua_d'),
  (:'r_canc',  :'ca', 'Parcela Cancelada',   '1980-01-03', '11999990023', true, NULL, :'ua_d'),
  (:'r_sent',  :'ca', 'Orcamento Enviado',   '1980-01-04', '11999990024', true, NULL, :'ua_d'),
  (:'r_draft', :'ca', 'Orcamento Rascunho',  '1980-01-05', '11999990025', true, NULL, :'ua_d'),
  (:'r_ret',   :'ca', 'Retencao e Parcela',  '1980-01-06', '11999990026', true, NULL, :'ua_d'),
  (:'r_appt',  :'ca', 'Agenda e Parcela',    '1980-01-07', '11999990027', true, NULL, :'ua_d');
:ctx_b_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, active, deactivated_at, created_by)
  VALUES (:'r_b', :'cb', 'Paciente de B', '1980-02-01', '11999990028', true, NULL, :'ub_d');
:ctx_a_d \gset

SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'r_open', :'pt1', :'bo', :'io', :'po');         -- parcela OPEN (100 de 300 pagos)
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'r_paid', :'pt1', :'bp', :'ip', :'pp');         -- vai ficar PAID
INSERT INTO core.payments (clinic_id, installment_id, kind, amount, method, paid_on, created_by) VALUES (:'ca', :'ip', 'PAYMENT', 200, 'PIX', DATE '2031-07-12', :'ua_d');
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'r_canc', :'pt1', :'bc', :'ic', :'pc');         -- vai ficar CANCELED (estorno + cancelamento)
INSERT INTO core.payments (clinic_id, installment_id, kind, amount, method, paid_on, reverses_payment_id, created_by)
  VALUES (:'ca', :'ic', 'REVERSAL', 100, 'PIX', DATE '2031-07-12', :'pc', :'ua_d');
UPDATE core.installments SET status = 'CANCELED' WHERE id = :'ic';
SELECT pg_temp.mk_budget_notes(:'ca', :'ua_d', :'r_sent', :'bs', :'pt1', 'SENT');
SELECT pg_temp.mk_budget_notes(:'ca', :'ua_d', :'r_draft', :'bd', :'pt1', 'DRAFT');
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'r_ret', :'pt1', :'br', :'ir', :'pr');          -- parcela OPEN + retenção vigente
INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by) VALUES (:'ca', :'r_ret', repeat('QUJD', 12), :'ua_d');
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'r_appt', :'pt1', :'ba', :'ia', :'pa_');        -- parcela OPEN + agendamento futuro
INSERT INTO core.appointments (clinic_id, patient_id, starts_at, ends_at, status, created_by)
  VALUES (:'ca', :'r_appt', date_trunc('minute', now()) + interval '10 days', date_trunc('minute', now()) + interval '10 days 30 minutes', 'SCHEDULED', :'ua_d');
:ctx_b_d \gset
SELECT pg_temp.f3_fixture(:'cb', :'ub_d', :'r_b', :'ptb', :'bb', :'ib', :'pb_');           -- outra clínica, com notes e PDF
:ctx_a_d \gset

-- foto do antes (como administrador): estado financeiro e de orçamento dos pacientes que serão anonimizados
CREATE TEMP TABLE snap_budgets AS SELECT id, number, status, total_amount, discount_amount, version FROM core.budgets;
CREATE TEMP TABLE snap_inst AS SELECT id, amount, status, number, due_date FROM core.installments;
CREATE TEMP TABLE snap_pay AS SELECT id, kind, amount, installment_id FROM core.payments;
CREATE TEMP TABLE snap_items AS SELECT id, budget_id, quantity, unit_price, description, line_total FROM core.budget_items;
CREATE TEMP TABLE snap_audit AS SELECT table_name, record_id, action, count(*) AS n FROM core.audit_log GROUP BY 1, 2, 3;

UPDATE core.patients SET active = false, deactivated_at = now() WHERE id IN (:'r_open', :'r_paid', :'r_canc', :'r_sent', :'r_draft', :'r_ret', :'r_appt');
:ctx_b_d \gset
UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = :'r_b';
:ctx_a_d \gset

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- ---------------------------------------------------------------------
-- T112: CR006
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T112 parcela OPEN (com 100 pagos)', 'CR006', pg_temp.anon_sql(:'r_open'));
-- precedência: CR002 (retenção) e CR004 (agenda) vêm antes do CR006
SELECT pg_temp.expect_err('T113 retencao vigente vem antes do CR006', 'CR002', pg_temp.anon_sql(:'r_ret'));
SELECT pg_temp.expect_err('T113 agendamento futuro vem antes do CR006', 'CR004', pg_temp.anon_sql(:'r_appt'));
-- a recusa não alterou nada
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_eq('T112 a recusa deixou as notes', pg_temp.q(format('SELECT notes FROM core.budgets WHERE id = %L', :'bo')), 'orcamento fixture');
SELECT pg_temp.expect_eq('T112 a recusa nao marcou o PDF', pg_temp.q(format('SELECT count(*) FILTER (WHERE deleted_at IS NOT NULL)::text FROM core.budget_documents WHERE budget_id = %L', :'bo')), '0');
SELECT pg_temp.expect_eq('T112 paciente continua nao anonimizado', pg_temp.q(format('SELECT (anonymized_at IS NULL)::text FROM core.patients WHERE id = %L', :'r_open')), 'true');
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- passa: parcelas só PAID, só CANCELED, orçamento SENT sem parcelas, orçamento DRAFT
SELECT pg_temp.expect_rows('T112 parcelas so PAID anonimiza', pg_temp.anon_sql(:'r_paid'), 1);
SELECT pg_temp.expect_rows('T112 parcelas so CANCELED anonimiza', pg_temp.anon_sql(:'r_canc'), 1);
SELECT pg_temp.expect_rows('T112 orcamento SENT sem parcelas anonimiza (nao e saldo em aberto)', pg_temp.anon_sql(:'r_sent'), 1);
SELECT pg_temp.expect_rows('T112 orcamento DRAFT anonimiza', pg_temp.anon_sql(:'r_draft'), 1);
-- outra clínica / recepção
:ctx_a_r \gset
SELECT pg_temp.expect_err('T112 recepcao nao anonimiza', '42501', pg_temp.anon_sql(:'r_open'));
:ctx_b_d \gset
SELECT pg_temp.expect_err('T112 dentista de B nao alcanca paciente de A', 'P0002', pg_temp.anon_sql(:'r_open'));
:ctx_a_d \gset
RESET SESSION AUTHORIZATION;

-- ---------------------------------------------------------------------
-- Conferência dos efeitos (como administrador)
-- ---------------------------------------------------------------------
DO $$
DECLARE
  anon constant uuid[] := ARRAY['f4000000-0000-4000-8000-000000000a02', 'f4000000-0000-4000-8000-000000000a03',
                                'f4000000-0000-4000-8000-000000000a04', 'f4000000-0000-4000-8000-000000000a05']::uuid[];
  keep constant uuid[] := ARRAY['f4000000-0000-4000-8000-000000000a01', 'f4000000-0000-4000-8000-000000000a06',
                                'f4000000-0000-4000-8000-000000000a07', 'f4000000-0000-4000-8000-000000000a08']::uuid[];
  n bigint;
BEGIN
  -- notes zeradas dos orçamentos dos 4 anonimizados; as dos demais ficam
  IF EXISTS (SELECT 1 FROM core.budgets WHERE patient_id = ANY (anon) AND notes IS NOT NULL) THEN
    RAISE EXCEPTION 'T112: budgets.notes do paciente anonimizado deveriam estar NULL';
  END IF;
  IF (SELECT count(*) FROM core.budgets WHERE patient_id = ANY (keep) AND notes IS NULL) <> 0 THEN
    RAISE EXCEPTION 'T112: so os orcamentos dos pacientes anonimizados podem ser limpos';
  END IF;
  -- o resto do orçamento permanece (número, status, total, desconto); version +1 só onde havia notes
  IF EXISTS (SELECT 1 FROM core.budgets b JOIN snap_budgets s ON s.id = b.id
              WHERE (b.number, b.status, b.total_amount, b.discount_amount) IS DISTINCT FROM (s.number, s.status, s.total_amount, s.discount_amount)) THEN
    RAISE EXCEPTION 'T112: numero, status, total e desconto do orcamento devem permanecer';
  END IF;
  IF EXISTS (SELECT 1 FROM core.budgets b JOIN snap_budgets s ON s.id = b.id
              WHERE b.patient_id = ANY (anon) AND b.version <> s.version + 1)
     OR EXISTS (SELECT 1 FROM core.budgets b JOIN snap_budgets s ON s.id = b.id WHERE b.patient_id = ANY (keep) AND b.version <> s.version) THEN
    RAISE EXCEPTION 'T112: version sobe 1 apenas nos orcamentos limpos';
  END IF;
  -- itens, parcelas e pagamentos permanecem idênticos (nada financeiro é tocado)
  IF (SELECT count(*) FROM core.budget_items) <> (SELECT count(*) FROM snap_items)
     OR EXISTS (SELECT 1 FROM core.budget_items i JOIN snap_items s ON s.id = i.id
                 WHERE (i.quantity, i.unit_price, i.description, i.line_total) IS DISTINCT FROM (s.quantity, s.unit_price, s.description, s.line_total)) THEN
    RAISE EXCEPTION 'T112: os itens devem permanecer';
  END IF;
  IF (SELECT count(*) FROM core.installments) <> (SELECT count(*) FROM snap_inst)
     OR EXISTS (SELECT 1 FROM core.installments i JOIN snap_inst s ON s.id = i.id
                 WHERE (i.amount, i.status, i.number, i.due_date) IS DISTINCT FROM (s.amount, s.status, s.number, s.due_date)) THEN
    RAISE EXCEPTION 'T112: as parcelas devem permanecer';
  END IF;
  IF (SELECT count(*) FROM core.payments) <> (SELECT count(*) FROM snap_pay)
     OR EXISTS (SELECT 1 FROM core.payments p JOIN snap_pay s ON s.id = p.id
                 WHERE (p.kind, p.amount, p.installment_id) IS DISTINCT FROM (s.kind, s.amount, s.installment_id)) THEN
    RAISE EXCEPTION 'T112: os pagamentos devem permanecer';
  END IF;
  -- PDFs: marcados só dos anonimizados (3 têm PDF: paid, canc; sent/draft não têm)
  IF EXISTS (SELECT 1 FROM core.budget_documents WHERE patient_id = ANY (anon) AND deleted_at IS NULL) THEN
    RAISE EXCEPTION 'T112: os PDFs do paciente anonimizado deveriam ter deleted_at';
  END IF;
  IF (SELECT count(*) FROM core.budget_documents WHERE patient_id = ANY (anon)) <> 2 THEN
    RAISE EXCEPTION 'T112: os PDFs nao podem ser apagados (so marcados)';
  END IF;
  IF EXISTS (SELECT 1 FROM core.budget_documents WHERE patient_id = ANY (keep) AND deleted_at IS NOT NULL) THEN
    RAISE EXCEPTION 'T112: so os PDFs dos anonimizados podem ser marcados';
  END IF;
  -- auditoria: 1 UPDATE por orçamento limpo (4) e por PDF marcado (2) alem do que ja existia; o ANONYMIZE de cada paciente
  SELECT count(*) INTO n FROM core.audit_log l
   WHERE l.table_name = 'budgets' AND l.action = 'UPDATE' AND l.record_id IN (SELECT id FROM core.budgets WHERE patient_id = ANY (anon));
  IF n - COALESCE((SELECT sum(s.n) FROM snap_audit s WHERE s.table_name = 'budgets' AND s.action = 'UPDATE'
                     AND s.record_id IN (SELECT id FROM core.budgets WHERE patient_id = ANY (anon))), 0) <> 4 THEN
    RAISE EXCEPTION 'T112: cada orcamento limpo gera 1 linha UPDATE no audit_log';
  END IF;
  SELECT count(*) INTO n FROM core.audit_log l
   WHERE l.table_name = 'budget_documents' AND l.action = 'UPDATE' AND l.record_id IN (SELECT id FROM core.budget_documents WHERE patient_id = ANY (anon));
  IF n <> 2 THEN RAISE EXCEPTION 'T112: cada PDF marcado gera 1 linha UPDATE no audit_log (obtido %)', n; END IF;
  IF (SELECT count(*) FROM core.audit_log WHERE action = 'ANONYMIZE' AND table_name = 'patients' AND record_id = ANY (anon)) <> 4 THEN
    RAISE EXCEPTION 'T112: faltou a linha ANONYMIZE';
  END IF;
  -- o paciente anonimizado ficou anonimizado; os que foram recusados, não
  IF (SELECT count(*) FROM core.patients WHERE id = ANY (anon) AND anonymized_at IS NOT NULL) <> 4
     OR (SELECT count(*) FROM core.patients WHERE id = ANY (keep) AND anonymized_at IS NOT NULL) <> 0 THEN
    RAISE EXCEPTION 'T112: estado de anonimizacao incorreto';
  END IF;
  -- outra clínica intacta
  IF (SELECT notes FROM core.budgets WHERE id = 'f4000000-0000-4000-8000-000000000b71') IS NULL
     OR EXISTS (SELECT 1 FROM core.budget_documents WHERE clinic_id = 'f1000000-0000-4000-8000-00000000000b' AND deleted_at IS NOT NULL) THEN
    RAISE EXCEPTION 'T112: a clinica B nao pode ser alcancada';
  END IF;
END $$;

-- depois de anonimizado: nenhum orçamento novo (CR003)
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T112 orcamento novo para paciente anonimizado', 'CR003', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'r_paid', :'ua_d'));
SELECT pg_temp.expect_err('T112 nao anonimiza duas vezes', 'CR003', pg_temp.anon_sql(:'r_paid'));

-- ---------------------------------------------------------------------
-- T113: quitado o saldo, o paciente antes recusado passa a poder ser anonimizado
-- ---------------------------------------------------------------------
-- r_open: paga os 200 que faltam (como dentista, pela API do banco) e anonimiza
SELECT pg_temp.expect_rows('T113 quita r_open', pg_temp.pay_sql(:'rev', :'ca', :'io', 'PAYMENT', '200', '2031-07-20', '', :'ua_d'), 1);
SELECT pg_temp.expect_rows('T113 depois de quitar, anonimiza', pg_temp.anon_sql(:'r_open'), 1);

RESET SESSION AUTHORIZATION;
ROLLBACK;
\o
SELECT 'anonymize_commercial: OK' AS resultado;
