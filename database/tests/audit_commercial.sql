-- =====================================================================
-- tests/audit_commercial.sql | Fase 3 / F06-commercial-audit
-- Teste 111 da spec global da Fase 3: toda gravação em budgets, installments, payments e budget_documents gera exatamente 1 linha em
-- audit_log (sem valores); os efeitos em cascata geram linhas próprias (recálculo do total = UPDATE em budgets; pagamento que quita ou
-- estorno que reabre = UPDATE em installments); procedure_types e budget_items NÃO geram linhas; sem app.user_id a gravação inteira falha
-- (28000); o log continua só de inserção e recusa nome de tabela fora da lista.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 28000 sem app.user_id; 23503 usuário de outra clínica; 23514 table_name fora da lista; 42501 log só de inserção.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set b1 'f4000000-0000-4000-8000-000000000901'
\set i1 'f4000000-0000-4000-8000-000000000902'
\set y1 'f4000000-0000-4000-8000-000000000903'
\set y2 'f4000000-0000-4000-8000-000000000904'
\set y3 'f4000000-0000-4000-8000-000000000905'
\set y4 'f4000000-0000-4000-8000-000000000906'
\set b2 'f4000000-0000-4000-8000-000000000907'

-- linhas de auditoria de uma tabela/registro/ação (o log é lido como administrador)
CREATE FUNCTION pg_temp.ac(p_table text, p_record text, p_action text) RETURNS bigint LANGUAGE sql AS $$
  SELECT count(*) FROM core.audit_log WHERE table_name = p_table AND record_id = p_record::uuid AND action = p_action $$;
CREATE FUNCTION pg_temp.expect_n(p_label text, p_actual bigint, p_expected bigint) RETURNS void LANGUAGE plpgsql AS $$
BEGIN IF p_actual <> p_expected THEN RAISE EXCEPTION '%: esperadas % linhas de auditoria, obtidas %', p_label, p_expected, p_actual; END IF; END $$;

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- orçamento: INSERT; item (recálculo = UPDATE do orçamento); desconto; envio; PDF; aprovação
SELECT pg_temp.expect_rows('orcamento', pg_temp.budget_sql(:'b1', :'ca', :'pa', :'ua_d'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T111 INSERT de orcamento', pg_temp.ac('budgets', :'b1', 'INSERT'), 1);
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('item (2 x pt1)', pg_temp.item_sql(:'ca', :'b1', :'pt1', '2', '1'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T111 item novo gera UPDATE no orcamento (total recalculado)', pg_temp.ac('budgets', :'b1', 'UPDATE'), 1);
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.audit_log WHERE table_name = 'budget_items') THEN
    RAISE EXCEPTION 'T111: budget_items nao gera auditoria';
  END IF;
END $$;
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('desconto', pg_temp.budget_discount_sql(:'b1', '0'), 1);
SELECT pg_temp.expect_rows('envia', pg_temp.budget_status_sql(:'b1', 'SENT'), 1);
SELECT pg_temp.expect_rows('PDF', pg_temp.doc_sql(:'ca', :'b1', :'pa', :'ua_d'), 1);
SELECT pg_temp.expect_rows('aprova', pg_temp.budget_status_sql(:'b1', 'APPROVED'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T111 UPDATEs do orcamento: item, desconto, envio, aprovacao = 4', pg_temp.ac('budgets', :'b1', 'UPDATE'), 4);
SELECT pg_temp.expect_n('T111 INSERT do PDF',
  (SELECT count(*) FROM core.audit_log WHERE table_name = 'budget_documents' AND action = 'INSERT'), 1);

-- parcela e pagamentos
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_ok_commit('parcela', ARRAY[pg_temp.inst_sql(:'i1', :'ca', :'b1', :'pa', '1', '300', '2031-10-01', :'ua_d')]);
SELECT pg_temp.expect_rows('pagamento parcial 100', pg_temp.pay_sql(:'y1', :'ca', :'i1', 'PAYMENT', '100', '2031-10-02', '', :'ua_d'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T111 INSERT da parcela', pg_temp.ac('installments', :'i1', 'INSERT'), 1);
SELECT pg_temp.expect_n('T111 INSERT do pagamento', pg_temp.ac('payments', :'y1', 'INSERT'), 1);
SELECT pg_temp.expect_n('T111 pagamento parcial nao mexe na parcela (sem UPDATE)', pg_temp.ac('installments', :'i1', 'UPDATE'), 0);
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('quita (200)', pg_temp.pay_sql(:'y2', :'ca', :'i1', 'PAYMENT', '200', '2031-10-03', '', :'ua_d'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T111 pagamento que quita gera UPDATE da parcela (status)', pg_temp.ac('installments', :'i1', 'UPDATE'), 1);
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('estorna o de 200', pg_temp.pay_sql(:'y3', :'ca', :'i1', 'REVERSAL', '200', '2031-10-04', :'y2', :'ua_d'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T111 estorno que reabre gera UPDATE da parcela', pg_temp.ac('installments', :'i1', 'UPDATE'), 2);
SELECT pg_temp.expect_n('T111 INSERT do estorno', pg_temp.ac('payments', :'y3', 'INSERT'), 1);
SELECT pg_temp.expect_n('T111 pagamentos nunca geram UPDATE nem DELETE no log',
  (SELECT count(*) FROM core.audit_log WHERE table_name = 'payments' AND action <> 'INSERT'), 0);

-- remoção lógica do PDF: UPDATE
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('remove o PDF', format('UPDATE core.budget_documents SET deleted_at = now() WHERE budget_id = %L', :'b1'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T111 UPDATE do PDF', (SELECT count(*) FROM core.audit_log WHERE table_name = 'budget_documents' AND action = 'UPDATE'), 1);
-- o dentista é o autor de todas as linhas (clínica A)
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.audit_log WHERE table_name IN ('budgets', 'installments', 'payments', 'budget_documents')
               AND (user_id <> 'f1000000-0000-4000-8000-0000000000a1' OR clinic_id <> 'f1000000-0000-4000-8000-00000000000a')) THEN
    RAISE EXCEPTION 'T111: a linha de auditoria deve trazer a clinica e o usuario da sessao';
  END IF;
END $$;

-- procedimentos não geram linhas (configuração)
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('procedimento novo', pg_temp.ptype_sql(gen_random_uuid()::text, :'ca', 'Limpeza', '90', '30', '#112233', :'ua_d'), 1);
SELECT pg_temp.expect_rows('procedimento alterado', format('UPDATE core.procedure_types SET base_price = 95 WHERE id = %L', :'pt1'), 1);
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.audit_log WHERE table_name = 'procedure_types') THEN RAISE EXCEPTION 'T111: procedure_types nao gera auditoria'; END IF;
  IF EXISTS (SELECT 1 FROM pg_trigger t WHERE NOT t.tgisinternal AND t.tgname LIKE '%audit%'
               AND t.tgrelid IN ('core.procedure_types'::regclass, 'core.budget_items'::regclass)) THEN
    RAISE EXCEPTION 'T111: procedure_types e budget_items nao devem ter trigger de auditoria';
  END IF;
END $$;

-- Sem app.user_id: a gravação inteira falha e NADA persiste (administrador: não passa pela RLS)
:ctx_a_d_nouser \gset
SELECT pg_temp.expect_err('T111 INSERT de orcamento sem app.user_id', '28000', pg_temp.budget_sql(:'b2', :'ca', :'pa', :'ua_d'));
SELECT pg_temp.expect_err('T111 UPDATE de orcamento sem app.user_id', '28000', pg_temp.budget_discount_sql(:'b1', '0'));
SELECT pg_temp.expect_err('T111 INSERT de pagamento sem app.user_id', '28000', pg_temp.pay_sql(:'y4', :'ca', :'i1', 'PAYMENT', '10', '2031-10-05', '', :'ua_d'));
SELECT pg_temp.expect_err('T111 INSERT de parcela sem app.user_id', '28000', pg_temp.inst_sql(gen_random_uuid()::text, :'ca', :'b1', :'pa', '2', '100', '2031-11-01', :'ua_d'));
SELECT pg_temp.expect_err('T111 INSERT de PDF sem app.user_id', '28000', pg_temp.doc_sql(:'ca', :'b1', :'pa', :'ua_d'));
SELECT pg_temp.expect_err('T111 UPDATE de parcela sem app.user_id', '28000', format('UPDATE core.installments SET due_date = DATE ''2031-12-30'' WHERE id = %L', :'i1'));
:ctx_a_d \gset
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.budgets WHERE id = 'f4000000-0000-4000-8000-000000000907')
     OR EXISTS (SELECT 1 FROM core.payments WHERE id = 'f4000000-0000-4000-8000-000000000906') THEN
    RAISE EXCEPTION 'T111: a gravacao sem auditoria nao pode persistir';
  END IF;
END $$;
-- usuário de outra clínica na sessão: a FK composta do log recusa
:ctx_a_d_user_b \gset
SELECT pg_temp.expect_err('T111 app.user_id de outra clinica', '23503', pg_temp.budget_discount_sql(:'b1', '0'));
:ctx_a_d \gset

-- O log: só identificadores, só de inserção, lista de tabelas ampliada e fechada
DO $$
BEGIN
  IF (SELECT array_agg(column_name::text ORDER BY column_name) FROM information_schema.columns
       WHERE table_schema = 'core' AND table_name = 'audit_log')
     IS DISTINCT FROM ARRAY['action', 'clinic_id', 'id', 'occurred_at', 'record_id', 'table_name', 'user_id'] THEN
    RAISE EXCEPTION 'T111: audit_log tem colunas fora do contrato (sem valores)';
  END IF;
END $$;
SELECT pg_temp.expect_err('T111 nome de tabela fora da lista (budget_items)', '23514', format(
  'INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action) VALUES (%L, %L, %L, gen_random_uuid(), %L)', :'ca', :'ua_d', 'budget_items', 'INSERT'));
SELECT pg_temp.expect_err('T111 nome de tabela fora da lista (procedure_types)', '23514', format(
  'INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action) VALUES (%L, %L, %L, gen_random_uuid(), %L)', :'ca', :'ua_d', 'procedure_types', 'INSERT'));
SAVEPOINT s_audit;
SELECT pg_temp.expect_err('T111 audit_log continua so de insercao (UPDATE)', '42501', 'UPDATE core.audit_log SET action = ''INSERT''');
SELECT pg_temp.expect_err('T111 audit_log continua so de insercao (DELETE)', '42501', 'DELETE FROM core.audit_log');
ROLLBACK TO SAVEPOINT s_audit;

-- Quem lê o log: só o dentista; a recepção vê 0 linhas
SET SESSION AUTHORIZATION crm_app;
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T111 recepcao nao le o log', 'SELECT 1 FROM core.audit_log', 0);
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T111 dentista le as linhas dos orcamentos', 'SELECT 1 FROM core.audit_log WHERE table_name = ''budgets''', 5);
RESET SESSION AUTHORIZATION;

ROLLBACK;
\o
SELECT 'audit_commercial: OK' AS resultado;
