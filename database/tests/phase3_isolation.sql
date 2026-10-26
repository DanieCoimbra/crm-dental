-- =====================================================================
-- tests/phase3_isolation.sql | Fase 3 / F08-phase3-verification
-- Testes 86 e 87 da spec global da Fase 3: isolamento de tenant nas 6 tabelas novas. Sob app.clinic_id = A, SELECT/UPDATE/DELETE não
-- alcançam linhas de B; INSERT com clinic_id = B falha (WITH CHECK); sem app.clinic_id nenhuma linha aparece e nada se grava (falha
-- fechada). E as FKs compostas recusam referência cruzada entre clínicas e entre pacientes.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 42501 WITH CHECK/privilégio; 23503 FK composta; 0 linhas afetadas = linha invisível para alterar.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set bA  'f4000000-0000-4000-8000-000000000c01'
\set iA  'f4000000-0000-4000-8000-000000000c02'
\set yA  'f4000000-0000-4000-8000-000000000c03'
\set bA2 'f4000000-0000-4000-8000-000000000c11'
\set iA2 'f4000000-0000-4000-8000-000000000c12'
\set yA2 'f4000000-0000-4000-8000-000000000c13'
\set bB  'f4000000-0000-4000-8000-000000000cb1'
\set iB  'f4000000-0000-4000-8000-000000000cb2'
\set yB  'f4000000-0000-4000-8000-000000000cb3'
\set apB 'f4000000-0000-4000-8000-000000000cb4'
\set apA 'f4000000-0000-4000-8000-000000000ca4'

:ctx_a_d \gset
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'pa', :'pt1', :'bA',  :'iA',  :'yA');
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'pa', :'pt1', :'bA2', :'iA2', :'yA2');
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by)
  VALUES (:'apA', :'ca', :'pa', '2031-11-01 08:00+00', '2031-11-01 08:30+00', 'SCHEDULED', :'ua_d');
:ctx_b_d \gset
SELECT pg_temp.f3_fixture(:'cb', :'ub_d', :'pb', :'ptb', :'bB', :'iB', :'yB');
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by)
  VALUES (:'apB', :'cb', :'pb', '2031-11-01 08:00+00', '2031-11-01 08:30+00', 'SCHEDULED', :'ub_d');
:ctx_a_d \gset

SET SESSION AUTHORIZATION crm_app;

-- ---------------------------------------------------------------------
-- T86: sessão da clínica A
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_rows('T86 A ve 2 procedimentos (so os seus)', 'SELECT 1 FROM core.procedure_types', 2);
SELECT pg_temp.expect_rows('T86 A ve 2 orcamentos', 'SELECT 1 FROM core.budgets', 2);
SELECT pg_temp.expect_rows('T86 A ve 2 itens', 'SELECT 1 FROM core.budget_items', 2);
SELECT pg_temp.expect_rows('T86 A ve 2 PDFs', 'SELECT 1 FROM core.budget_documents', 2);
SELECT pg_temp.expect_rows('T86 A ve 2 parcelas', 'SELECT 1 FROM core.installments', 2);
SELECT pg_temp.expect_rows('T86 A ve 2 pagamentos', 'SELECT 1 FROM core.payments', 2);
-- UPDATE/DELETE em linha de B: invisível (0 linhas, sem erro)
SELECT pg_temp.expect_rows('T86 UPDATE em procedimento de B', format('UPDATE core.procedure_types SET base_price = 1 WHERE id = %L', :'ptb'), 0);
SELECT pg_temp.expect_rows('T86 UPDATE em orcamento de B', format('UPDATE core.budgets SET notes = ''x'' WHERE id = %L', :'bB'), 0);
SELECT pg_temp.expect_rows('T86 UPDATE em item de B', format('UPDATE core.budget_items SET quantity = 9 WHERE budget_id = %L', :'bB'), 0);
SELECT pg_temp.expect_rows('T86 DELETE em item de B', format('DELETE FROM core.budget_items WHERE budget_id = %L', :'bB'), 0);
SELECT pg_temp.expect_rows('T86 UPDATE em PDF de B', format('UPDATE core.budget_documents SET deleted_at = now() WHERE budget_id = %L', :'bB'), 0);
SELECT pg_temp.expect_rows('T86 UPDATE em parcela de B', format('UPDATE core.installments SET due_date = DATE ''2031-12-31'' WHERE id = %L', :'iB'), 0);
-- INSERT com clinic_id = B falha pelo WITH CHECK
SELECT pg_temp.expect_err('T86 INSERT de procedimento em B', '42501', pg_temp.ptype_sql(gen_random_uuid()::text, :'cb', 'Intruso', '10', '30', '#112233', :'ua_d'));
SELECT pg_temp.expect_err('T86 INSERT de orcamento em B', '42501', pg_temp.budget_sql(gen_random_uuid()::text, :'cb', :'pb', :'ua_d'));
SELECT pg_temp.expect_err('T86 INSERT de item em B', '42501', pg_temp.item_sql(:'cb', :'bB', :'ptb', '1', '2'));
SELECT pg_temp.expect_err('T86 INSERT de PDF em B', '42501', pg_temp.doc_sql(:'cb', :'bB', :'pb', :'ua_d'));
SELECT pg_temp.expect_err('T86 INSERT de parcela em B', '42501', pg_temp.inst_sql(gen_random_uuid()::text, :'cb', :'bB', :'pb', '2', '100', '2031-12-01', :'ua_d'));
SELECT pg_temp.expect_err('T86 INSERT de pagamento em B', '42501', pg_temp.pay_sql(gen_random_uuid()::text, :'cb', :'iB', 'PAYMENT', '10', '2031-12-01', '', :'ua_d'));
-- pagamento é append-only também para a própria clínica
SELECT pg_temp.expect_err('T86 UPDATE de pagamento (nem o da propria clinica)', '42501', format('UPDATE core.payments SET amount = 1 WHERE id = %L', :'yA'));

-- sem app.clinic_id (nem papel nem usuário): nenhuma linha, nada se grava
:ctx_none \gset
SELECT pg_temp.expect_rows('T86 sem sessao: 0 procedimentos', 'SELECT 1 FROM core.procedure_types', 0);
SELECT pg_temp.expect_rows('T86 sem sessao: 0 orcamentos', 'SELECT 1 FROM core.budgets', 0);
SELECT pg_temp.expect_rows('T86 sem sessao: 0 itens', 'SELECT 1 FROM core.budget_items', 0);
SELECT pg_temp.expect_rows('T86 sem sessao: 0 PDFs', 'SELECT 1 FROM core.budget_documents', 0);
SELECT pg_temp.expect_rows('T86 sem sessao: 0 parcelas', 'SELECT 1 FROM core.installments', 0);
SELECT pg_temp.expect_rows('T86 sem sessao: 0 pagamentos', 'SELECT 1 FROM core.payments', 0);
SELECT pg_temp.expect_err('T86 sem sessao: INSERT de procedimento', '42501', pg_temp.ptype_sql(gen_random_uuid()::text, :'ca', 'Sem sessao', '10', '30', '#112233', :'ua_d'));
SELECT pg_temp.expect_err('T86 sem sessao: INSERT de orcamento', '42501', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d'));
SELECT pg_temp.expect_err('T86 sem sessao: INSERT de pagamento', '42501', pg_temp.pay_sql(gen_random_uuid()::text, :'ca', :'iA', 'PAYMENT', '10', '2031-12-01', '', :'ua_d'));
-- só com app.clinic_id (sem papel): também nada
SELECT set_config('app.clinic_id', :'ca', true) AS _ \gset
SELECT pg_temp.expect_rows('T86 so com a clinica (sem papel): 0 orcamentos', 'SELECT 1 FROM core.budgets', 0);

-- a clínica B enxerga só o que é dela
:ctx_b_d \gset
SELECT pg_temp.expect_rows('T86 B ve 1 procedimento', 'SELECT 1 FROM core.procedure_types', 1);
SELECT pg_temp.expect_rows('T86 B ve 1 orcamento', 'SELECT 1 FROM core.budgets', 1);
SELECT pg_temp.expect_rows('T86 B ve 1 pagamento', 'SELECT 1 FROM core.payments', 1);

-- ---------------------------------------------------------------------
-- T87: FKs cruzadas
-- ---------------------------------------------------------------------
:ctx_a_d \gset
SELECT pg_temp.expect_err('T87 agendamento de A com procedimento de B', '23503',
  format('INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, procedure_type_id, created_by) VALUES (gen_random_uuid(), %L, %L, ''2031-11-02 08:00+00'', ''2031-11-02 08:30+00'', ''SCHEDULED'', %L, %L)',
         :'ca', :'pa', :'ptb', :'ua_d'));
SELECT pg_temp.expect_rows('T87 orcamento DRAFT novo para os itens cruzados', pg_temp.budget_sql('f4000000-0000-4000-8000-000000000cc1', :'ca', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_err('T87 item de procedimento de outra clinica', '23503', pg_temp.item_sql(:'ca', 'f4000000-0000-4000-8000-000000000cc1', :'ptb', '1', '1'));
SELECT pg_temp.expect_err('T87 orcamento de A com atendimento de B', '23503', format(
  'INSERT INTO core.budgets (id, clinic_id, patient_id, appointment_id, created_by) VALUES (gen_random_uuid(), %L, %L, %L, %L)', :'ca', :'pa', :'apB', :'ua_d'));
SELECT pg_temp.expect_err('T87 orcamento de A com paciente de B', '23503', pg_temp.budget_sql(gen_random_uuid()::text, :'ca', :'pb', :'ua_d'));
SELECT pg_temp.expect_err('T87 PDF com paciente diferente do orcamento', '23503', pg_temp.doc_sql(:'ca', :'bA', :'pa2', :'ua_d'));
SELECT pg_temp.expect_err('T87 parcela com paciente diferente do orcamento', '23503', pg_temp.inst_sql(gen_random_uuid()::text, :'ca', :'bA', :'pa2', '2', '100', '2031-12-01', :'ua_d'));
SELECT pg_temp.expect_err('T87 parcela de orcamento de B (invisivel: FK composta)', '23503', pg_temp.inst_sql(gen_random_uuid()::text, :'ca', :'bB', :'pb', '2', '100', '2031-12-01', :'ua_d'));
SELECT pg_temp.expect_err('T87 estorno aponta pagamento de OUTRA parcela da mesma clinica', '23503', pg_temp.pay_sql(gen_random_uuid()::text, :'ca', :'iA', 'REVERSAL', '100', '2031-12-01', :'yA2', :'ua_d'));
SELECT pg_temp.expect_err('T87 estorno aponta pagamento de OUTRA clinica', '23503', pg_temp.pay_sql(gen_random_uuid()::text, :'ca', :'iA', 'REVERSAL', '100', '2031-12-01', :'yB', :'ua_d'));
SELECT pg_temp.expect_err('T87 pagamento da clinica A em parcela de B (FK composta)', '23503', pg_temp.pay_sql(gen_random_uuid()::text, :'ca', :'iB', 'PAYMENT', '10', '2031-12-01', '', :'ua_d'));

RESET SESSION AUTHORIZATION;
ROLLBACK;
\o
SELECT 'phase3_isolation: OK' AS resultado;
