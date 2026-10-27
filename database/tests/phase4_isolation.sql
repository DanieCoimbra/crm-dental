-- =====================================================================
-- tests/phase4_isolation.sql | Fase 4 / F06-phase4-verification (teste 138)
-- Isolamento de clinical_documents e de users.cro_*: com duas clínicas, o dentista de uma não lê, não altera, não apaga nem insere na
-- outra (WITH CHECK, FK composta); sem sessão nenhuma linha aparece; o crm_backup lê as duas (política nomeada, sem BYPASSRLS).
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 42501 (WITH CHECK, grant); 23503 FK composta; 0 linhas (RLS).
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase4_seed.sql

\set dA 'f6000000-0000-4000-8000-000000000041'
\set dB 'f6000000-0000-4000-8000-000000000042'

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('dA', pg_temp.cdoc_sql(:'dA', :'ca', :'pa', :'ua_d', 'CERTIFICATE'), 1);
:ctx_b_d \gset
SELECT pg_temp.expect_rows('dB', pg_temp.cdoc_sql(:'dB', :'cb', :'pb', :'ub_d', 'CERTIFICATE'), 1);

-- B não vê nem mexe em A
SELECT pg_temp.expect_rows('T138 B le so os seus documentos', 'SELECT 1 FROM core.clinical_documents', 1);
SELECT pg_temp.expect_rows('T138 B nao le o documento de A', format('SELECT 1 FROM core.clinical_documents WHERE id = %L', :'dA'), 0);
SELECT pg_temp.expect_rows('T138 B nao anula o documento de A', pg_temp.cdoc_void_sql(:'dA', :'ub_d'), 0);
SELECT pg_temp.expect_err('T138 B nao apaga (sem grant)', '42501', format('DELETE FROM core.clinical_documents WHERE id = %L', :'dA'));
SELECT pg_temp.expect_err('T138 B insere com clinic_id de A', '42501', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', 'PRESCRIPTION'));
SELECT pg_temp.expect_err('T138 B insere em B com paciente de A (FK composta)', '23503', pg_temp.cdoc_sql(gen_random_uuid()::text, :'cb', :'pa', :'ub_d', 'PRESCRIPTION'));
SELECT pg_temp.expect_err('T138 B insere em B com created_by de A (FK composta ou WITH CHECK)', '42501', pg_temp.cdoc_sql(gen_random_uuid()::text, :'cb', :'pb', :'ua_d', 'PRESCRIPTION'));
SELECT pg_temp.expect_rows('T138 B nao le o usuario de A (CRO)', format('SELECT cro_number FROM core.users WHERE id = %L', :'ua_d'), 0);
SELECT pg_temp.expect_rows('T138 B le o proprio CRO', format('SELECT cro_number FROM core.users WHERE id = %L', :'ub_d'), 1);

-- A não vê B
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T138 A le so os seus documentos', 'SELECT 1 FROM core.clinical_documents', 1);
SELECT pg_temp.expect_rows('T138 A nao le o documento de B', format('SELECT 1 FROM core.clinical_documents WHERE id = %L', :'dB'), 0);
SELECT pg_temp.expect_rows('T138 A nao anula o documento de B', pg_temp.cdoc_void_sql(:'dB', :'ua_d'), 0);

-- sem sessão: nenhuma linha (clínica vazia, papel vazio, nada)
:ctx_none \gset
SELECT pg_temp.expect_rows('T138 sem sessao nao le documentos', 'SELECT 1 FROM core.clinical_documents', 0);
SELECT pg_temp.expect_rows('T138 sem sessao nao anula', pg_temp.cdoc_void_sql(:'dA', :'ua_d'), 0);
SELECT pg_temp.expect_err('T138 sem sessao nao insere', '42501', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', 'PRESCRIPTION'));
-- clínica certa, papel vazio: nada
:ctx_a_norole \gset
SELECT pg_temp.expect_rows('T138 clinica sem papel nao le documentos', 'SELECT 1 FROM core.clinical_documents', 0);
RESET SESSION AUTHORIZATION;

-- crm_backup lê as duas clínicas pela política nomeada; não escreve
SET SESSION AUTHORIZATION crm_backup;
:ctx_none \gset
SELECT pg_temp.expect_rows('T138 crm_backup le os documentos das duas clinicas', 'SELECT 1 FROM core.clinical_documents', 2);
SELECT pg_temp.expect_err('T138 crm_backup nao insere', '42501', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', 'PRESCRIPTION'));
SELECT pg_temp.expect_err('T138 crm_backup nao anula', '42501', pg_temp.cdoc_void_sql(:'dA', :'ua_d'));
RESET SESSION AUTHORIZATION;

ROLLBACK;
\o
SELECT 'phase4_isolation: OK' AS resultado;
