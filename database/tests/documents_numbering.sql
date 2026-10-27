-- =====================================================================
-- tests/documents_numbering.sql | Fase 4 / F02-clinical-documents (teste 130)
-- Numeração por (clinic_id, document_type) atribuída pelo banco: 1, 2, 3...; tipos independentes; clínicas independentes; o número
-- do cliente é sobrescrito; documento anulado mantém o número e o próximo continua a série; ROLLBACK de uma emissão devolve o número
-- (max + 1 sobre o que está confirmado: sem repetição entre documentos existentes). A corrida entre conexões está em
-- documents_concurrency.sh (teste 131).
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase4_seed.sql

CREATE FUNCTION pg_temp.num(p_id text) RETURNS integer LANGUAGE sql AS $$ SELECT number FROM core.clinical_documents WHERE id = p_id::uuid $$;
CREATE FUNCTION pg_temp.newid() RETURNS text LANGUAGE sql AS $$ SELECT gen_random_uuid()::text $$;

SELECT pg_temp.newid() AS c1 \gset
SELECT pg_temp.newid() AS c2 \gset
SELECT pg_temp.newid() AS c3 \gset
SELECT pg_temp.newid() AS r1 \gset
SELECT pg_temp.newid() AS r2 \gset
SELECT pg_temp.newid() AS f1 \gset
SELECT pg_temp.newid() AS bc1 \gset
SELECT pg_temp.newid() AS c4 \gset

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('c1', pg_temp.cdoc_sql(:'c1', :'ca', :'pa', :'ua_d', 'CERTIFICATE'), 1);
SELECT pg_temp.expect_rows('c2 (outro paciente da mesma clinica)', pg_temp.cdoc_sql(:'c2', :'ca', :'pa2', :'ua_d', 'CERTIFICATE'), 1);
SELECT pg_temp.expect_rows('c3', pg_temp.cdoc_sql(:'c3', :'ca', :'pa', :'ua_d', 'CERTIFICATE'), 1);
SELECT pg_temp.expect_rows('r1', pg_temp.cdoc_sql(:'r1', :'ca', :'pa', :'ua_d', 'PRESCRIPTION'), 1);
SELECT pg_temp.expect_rows('f1', pg_temp.cdoc_sql(:'f1', :'ca', :'pa', :'ua_d', 'REFERRAL'), 1);
SELECT pg_temp.expect_rows('r2', pg_temp.cdoc_sql(:'r2', :'ca', :'pa', :'ua_d', 'PRESCRIPTION'), 1);
:ctx_b_d \gset
SELECT pg_temp.expect_rows('bc1 (clinica B)', pg_temp.cdoc_sql(:'bc1', :'cb', :'pb', :'ub_d', 'CERTIFICATE'), 1);
RESET SESSION AUTHORIZATION;

SELECT pg_temp.expect_n('T130 atestado 1', pg_temp.num(:'c1'), 1);
SELECT pg_temp.expect_n('T130 atestado 2 (mesma clinica, outro paciente)', pg_temp.num(:'c2'), 2);
SELECT pg_temp.expect_n('T130 atestado 3', pg_temp.num(:'c3'), 3);
SELECT pg_temp.expect_n('T130 receita 1 (serie propria)', pg_temp.num(:'r1'), 1);
SELECT pg_temp.expect_n('T130 receita 2', pg_temp.num(:'r2'), 2);
SELECT pg_temp.expect_n('T130 encaminhamento 1 (serie propria)', pg_temp.num(:'f1'), 1);
SELECT pg_temp.expect_n('T130 atestado 1 da clinica B (serie propria)', pg_temp.num(:'bc1'), 1);

-- anulado mantém o número; o próximo continua a série (nunca reutiliza o de um documento existente)
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('anula c3', pg_temp.cdoc_void_sql(:'c3', :'ua_d'), 1);
SELECT pg_temp.expect_rows('c4', pg_temp.cdoc_sql(:'c4', :'ca', :'pa', :'ua_d', 'CERTIFICATE'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T130 anulado mantem o numero 3', pg_temp.num(:'c3'), 3);
SELECT pg_temp.expect_n('T130 proximo atestado e o 4 (nao reutiliza o do anulado)', pg_temp.num(:'c4'), 4);

-- ROLLBACK de uma emissão (savepoint): a numeração não deixa duplicidade entre documentos existentes
SELECT pg_temp.newid() AS c5 \gset
SELECT pg_temp.newid() AS c6 \gset
SAVEPOINT emissao;
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('c5 (sera desfeito)', pg_temp.cdoc_sql(:'c5', :'ca', :'pa', :'ua_d', 'CERTIFICATE'), 1);
RESET SESSION AUTHORIZATION;
ROLLBACK TO SAVEPOINT emissao;
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('c6', pg_temp.cdoc_sql(:'c6', :'ca', :'pa', :'ua_d', 'CERTIFICATE'), 1);
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_n('T130 depois do rollback o numero seguinte e o 5', pg_temp.num(:'c6'), 5);

-- invariantes finais: sem repetição por (clinica, tipo) e série contínua
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.clinical_documents GROUP BY clinic_id, document_type, number HAVING count(*) > 1) THEN
    RAISE EXCEPTION 'T130: numero repetido';
  END IF;
  IF (SELECT max(number) FROM core.clinical_documents WHERE clinic_id = 'f1000000-0000-4000-8000-00000000000a' AND document_type = 'CERTIFICATE') <> 5 THEN
    RAISE EXCEPTION 'T130: serie de atestados da clinica A deveria terminar em 5';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'documents_numbering: OK' AS resultado;
