-- =====================================================================
-- tests/clinical_documents_immutable.sql | Fase 4 / F02-clinical-documents (teste 129)
-- O documento é IMUTÁVEL: crm_app só anula (voided_at, voided_by, void_reason, version) e uma vez; nenhum papel apaga ou esvazia
-- (DELETE e TRUNCATE negados até ao dono e ao superusuário); a exceção de anonimização não se forja pelo GUC.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 42501 sem grant, DELETE/TRUNCATE; 23514 transição inválida ou campo imutável; 0 linhas para o dono sob RLS FORCE.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase4_seed.sql

\set d1 'f6000000-0000-4000-8000-000000000011'
\set d2 'f6000000-0000-4000-8000-000000000012'
\set d3 'f6000000-0000-4000-8000-000000000013'

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('d1', pg_temp.cdoc_sql(:'d1', :'ca', :'pa', :'ua_d', 'CERTIFICATE'), 1);
SELECT pg_temp.expect_rows('d2', pg_temp.cdoc_sql(:'d2', :'ca', :'pa', :'ua_d', 'PRESCRIPTION'), 1);
SELECT pg_temp.expect_rows('d3', pg_temp.cdoc_sql(:'d3', :'ca', :'pa', :'ua_d', 'REFERRAL'), 1);

-- sem grant de coluna: 42501
SELECT pg_temp.expect_err('T129 crm_app nao altera o conteudo', '42501', format('UPDATE core.clinical_documents SET content = %L WHERE id = %L', repeat('B', 60), :'d1'));
SELECT pg_temp.expect_err('T129 crm_app nao altera o sha256', '42501', format('UPDATE core.clinical_documents SET sha256 = repeat(''b'', 64) WHERE id = %L', :'d1'));
SELECT pg_temp.expect_err('T129 crm_app nao altera o tipo', '42501', format('UPDATE core.clinical_documents SET document_type = ''PRESCRIPTION'' WHERE id = %L', :'d1'));
SELECT pg_temp.expect_err('T129 crm_app nao altera o numero', '42501', format('UPDATE core.clinical_documents SET number = 50 WHERE id = %L', :'d1'));
SELECT pg_temp.expect_err('T129 crm_app nao altera rest_days', '42501', format('UPDATE core.clinical_documents SET rest_days = 30 WHERE id = %L', :'d1'));
SELECT pg_temp.expect_err('T129 crm_app nao anonimiza', '42501', format('UPDATE core.clinical_documents SET anonymized_at = now() WHERE id = %L', :'d1'));
SELECT pg_temp.expect_err('T129 crm_app nao muda o paciente', '42501', format('UPDATE core.clinical_documents SET patient_id = %L WHERE id = %L', :'pa2', :'d1'));
SELECT pg_temp.expect_err('T129 crm_app nao faz DELETE', '42501', format('DELETE FROM core.clinical_documents WHERE id = %L', :'d1'));
SELECT pg_temp.expect_err('T129 crm_app nao faz TRUNCATE', '42501', 'TRUNCATE core.clinical_documents');

-- anular: só com voided_by = app.user_id; uma vez
SELECT pg_temp.expect_err('T129 anular em nome de outro usuario', '23514', pg_temp.cdoc_void_sql(:'d1', :'ua_r'));
SELECT pg_temp.expect_err('T129 anular so com voided_at sem voided_by (CHECK)', '23514',
  format('UPDATE core.clinical_documents SET voided_at = now(), version = version + 1 WHERE id = %L', :'d1'));
SELECT pg_temp.expect_err('T129 UPDATE sem anular (so a version)', '23514', format('UPDATE core.clinical_documents SET version = version + 1 WHERE id = %L', :'d1'));
SELECT pg_temp.expect_rows('T129 anular', pg_temp.cdoc_void_sql(:'d1', :'ua_d'), 1);
SELECT pg_temp.expect_err('T129 anular de novo', '23514', pg_temp.cdoc_void_sql(:'d1', :'ua_d'));
SELECT pg_temp.expect_err('T129 desfazer a anulacao', '23514',
  format('UPDATE core.clinical_documents SET voided_at = NULL, voided_by = NULL, void_reason = NULL, version = version + 1 WHERE id = %L', :'d1'));
SELECT pg_temp.expect_rows('T129 anular sem motivo (void_reason nulo)',
  format('UPDATE core.clinical_documents SET voided_at = now(), voided_by = %L, version = version + 1 WHERE id = %L', :'ua_d', :'d2'), 1);
SELECT pg_temp.expect_rows('T129 o anulado continua legivel', format('SELECT 1 FROM core.clinical_documents WHERE id = %L AND voided_at IS NOT NULL', :'d1'), 1);

-- recepção não anula nem apaga (0 linhas)
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T129 recepcao nao anula', pg_temp.cdoc_void_sql(:'d3', :'ua_r'), 0);
RESET SESSION AUTHORIZATION;

-- dono: a RLS FORCE sem política nega o acesso (0 linhas, sem erro); TRUNCATE e DELETE de linha não passam pelo trigger do dono... passam
SET LOCAL ROLE crm_owner;
SELECT pg_temp.expect_rows('T129 dono nao enxerga documentos (RLS FORCE sem politica)', format('SELECT 1 FROM core.clinical_documents WHERE id = %L', :'d3'), 0);
SELECT pg_temp.expect_rows('T129 DELETE do dono afeta 0 linhas', format('DELETE FROM core.clinical_documents WHERE id = %L', :'d3'), 0);
SELECT pg_temp.expect_err('T129 TRUNCATE do dono negado pelo trigger', '42501', 'TRUNCATE core.clinical_documents');
RESET ROLE;

-- superusuário (ignora a RLS): os triggers negam
SELECT pg_temp.expect_err('T129 DELETE de superusuario negado', '42501', format('DELETE FROM core.clinical_documents WHERE id = %L', :'d3'));
SELECT pg_temp.expect_err('T129 TRUNCATE de superusuario negado', '42501', 'TRUNCATE core.clinical_documents');
SELECT pg_temp.expect_err('T129 TRUNCATE ... CASCADE vindo de patients tambem negado', '42501', 'TRUNCATE core.patients CASCADE');
SELECT pg_temp.expect_err('T129 superusuario nao altera o conteudo', '23514', format('UPDATE core.clinical_documents SET content = %L WHERE id = %L', repeat('B', 60), :'d3'));
SELECT pg_temp.expect_err('T129 superusuario nao muda o tipo (trigger de imutaveis)', '23514', format('UPDATE core.clinical_documents SET document_type = ''PRESCRIPTION'' WHERE id = %L', :'d3'));
SELECT pg_temp.expect_err('T129 superusuario nao muda o sha256', '23514', format('UPDATE core.clinical_documents SET sha256 = repeat(''b'', 64) WHERE id = %L', :'d3'));

-- a exceção de anonimização não se forja: GUC ligado por sessão comum (crm_app sem grant; superusuário não é crm_definer)
SELECT set_config('core.anonymizing', 'on', true) \gset
SELECT pg_temp.expect_err('T129 GUC forjado por superusuario nao anonimiza', '23514',
  format('UPDATE core.clinical_documents SET content = NULL, rest_days = NULL, target_specialty = NULL, void_reason = NULL, anonymized_at = now() WHERE id = %L', :'d3'));
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT set_config('core.anonymizing', 'on', true) \gset
SELECT pg_temp.expect_err('T129 GUC forjado por crm_app nao anonimiza', '42501',
  format('UPDATE core.clinical_documents SET anonymized_at = now() WHERE id = %L', :'d3'));
RESET SESSION AUTHORIZATION;
SELECT set_config('core.anonymizing', 'off', true) \gset

-- nada mudou além das anulações
DO $$
BEGIN
  IF (SELECT count(*) FROM core.clinical_documents) <> 3 THEN RAISE EXCEPTION 'T129: contagem de documentos mudou'; END IF;
  IF (SELECT count(*) FROM core.clinical_documents WHERE voided_at IS NOT NULL) <> 2 THEN RAISE EXCEPTION 'T129: esperadas 2 anulacoes'; END IF;
  IF EXISTS (SELECT 1 FROM core.clinical_documents WHERE content IS NULL OR anonymized_at IS NOT NULL) THEN RAISE EXCEPTION 'T129: documento alterado'; END IF;
END $$;

ROLLBACK;
\o
SELECT 'clinical_documents_immutable: OK' AS resultado;
