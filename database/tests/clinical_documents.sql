-- =====================================================================
-- tests/clinical_documents.sql | Fase 4 / F02-clinical-documents (testes 127 e 128)
--   127  constraints: os 3 tipos emitem; campos obrigatórios e proibidos por tipo; limites de rest_days e da especialidade; Base64 e
--        tamanho mínimo do conteúdo; sha256; tipo inválido; FKs compostas (consulta de outro paciente, paciente inexistente);
--        o número enviado pelo cliente é ignorado
--   128  guardas do INSERT: recepção (42501, antes de CR012), emissor sem CRO (CR012), paciente anonimizado (CR003) e inativo (CR005),
--        clínica alheia e created_by alheio (42501), recepção não lê nem atualiza (0 linhas)
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 23514 CHECK; 23503 FK; 42501 papel/RLS; CR012; CR003; CR005.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase4_seed.sql

\set d1 'f6000000-0000-4000-8000-000000000001'
\set d2 'f6000000-0000-4000-8000-000000000002'
\set d3 'f6000000-0000-4000-8000-000000000003'
\set apA 'f6000000-0000-4000-8000-0000000000a1'

-- consulta do paciente pa (para o vínculo opcional)
SELECT pg_temp.expect_rows('consulta de pa', pg_temp.appt_sql(:'apA', :'ca', :'pa', :'ua_d', '2031-07-01T12:00:00Z', '2031-07-01T12:30:00Z', 'SCHEDULED'), 1);

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- 127: os 3 tipos
SELECT pg_temp.expect_rows('T127 atestado', pg_temp.cdoc_sql(:'d1', :'ca', :'pa', :'ua_d', 'CERTIFICATE'), 1);
SELECT pg_temp.expect_rows('T127 receita', pg_temp.cdoc_sql(:'d2', :'ca', :'pa', :'ua_d', 'PRESCRIPTION'), 1);
SELECT pg_temp.expect_rows('T127 encaminhamento', pg_temp.cdoc_sql(:'d3', :'ca', :'pa', :'ua_d', 'REFERRAL'), 1);

-- campos por tipo
SELECT pg_temp.expect_err('T127 atestado sem rest_days', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'CERTIFICATE', 'NULL', 'NULL', quote_literal(pg_temp.b64())));
SELECT pg_temp.expect_err('T127 rest_days = 0', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'CERTIFICATE', '0', 'NULL', quote_literal(pg_temp.b64())));
SELECT pg_temp.expect_err('T127 rest_days = 91', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'CERTIFICATE', '91', 'NULL', quote_literal(pg_temp.b64())));
SELECT pg_temp.expect_rows('T127 rest_days = 90 (limite)', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'CERTIFICATE', '90', 'NULL', quote_literal(pg_temp.b64())), 1);
SELECT pg_temp.expect_err('T127 rest_days na receita', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'PRESCRIPTION', '3', 'NULL', quote_literal(pg_temp.b64())));
SELECT pg_temp.expect_err('T127 especialidade no atestado', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'CERTIFICATE', '3', quote_literal('Endodontia'), quote_literal(pg_temp.b64())));
SELECT pg_temp.expect_err('T127 encaminhamento sem especialidade', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'REFERRAL', 'NULL', 'NULL', quote_literal(pg_temp.b64())));
SELECT pg_temp.expect_err('T127 especialidade de 1 caractere', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'REFERRAL', 'NULL', quote_literal('E'), quote_literal(pg_temp.b64())));
SELECT pg_temp.expect_err('T127 especialidade de 61 caracteres', '22001', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'REFERRAL', 'NULL', quote_literal(repeat('E', 61)), quote_literal(pg_temp.b64())));
SELECT pg_temp.expect_err('T127 rest_days no encaminhamento', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'REFERRAL', '2', quote_literal('Endodontia'), quote_literal(pg_temp.b64())));
SELECT pg_temp.expect_err('T127 receita com especialidade', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'PRESCRIPTION', 'NULL', quote_literal('Endodontia'), quote_literal(pg_temp.b64())));

-- conteúdo
SELECT pg_temp.expect_err('T127 conteudo curto demais', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'PRESCRIPTION', 'NULL', 'NULL', quote_literal('QUJD')));
SELECT pg_temp.expect_err('T127 conteudo fora do Base64', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'PRESCRIPTION', 'NULL', 'NULL', quote_literal(repeat('!', 60))));
SELECT pg_temp.expect_err('T127 conteudo em texto claro com espacos', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'PRESCRIPTION', 'NULL', 'NULL', quote_literal(repeat('amoxicilina 500mg ', 5))));
SELECT pg_temp.expect_err('T127 conteudo nulo', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'PRESCRIPTION', 'NULL', 'NULL', 'NULL'));
SELECT pg_temp.expect_err('T127 conteudo acima de 22000', '22001', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'PRESCRIPTION', 'NULL', 'NULL', quote_literal(repeat('A', 22001))));
SELECT pg_temp.expect_rows('T127 conteudo de 22000 caracteres', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'PRESCRIPTION', 'NULL', 'NULL', quote_literal(repeat('A', 22000))), 1);
SELECT pg_temp.expect_err('T127 sha256 em maiusculas', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'PRESCRIPTION', 'NULL', 'NULL', quote_literal(pg_temp.b64()), repeat('A', 64)));
SELECT pg_temp.expect_err('T127 sha256 com 63 caracteres', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'PRESCRIPTION', 'NULL', 'NULL', quote_literal(pg_temp.b64()), repeat('a', 63)));
SELECT pg_temp.expect_err('T127 tipo invalido', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'LAUDO', 'NULL', 'NULL', quote_literal(pg_temp.b64())));
SELECT pg_temp.expect_err('T127 tipo em minusculas', '23514', pg_temp.cdoc_raw_sql(:'ca', :'pa', :'ua_d', 'certificate', '3', 'NULL', quote_literal(pg_temp.b64())));

-- FKs compostas
SELECT pg_temp.expect_rows('T127 documento vinculado a consulta do mesmo paciente',
  format('INSERT INTO core.clinical_documents (clinic_id, patient_id, appointment_id, document_type, issued_on, content, sha256, created_by) '
         'VALUES (%L, %L, %L, ''PRESCRIPTION'', DATE ''2031-07-01'', %L, repeat(''a'', 64), %L)', :'ca', :'pa', :'apA', pg_temp.b64(), :'ua_d'), 1);
SELECT pg_temp.expect_err('T127 consulta de OUTRO paciente (FK composta)', '23503',
  format('INSERT INTO core.clinical_documents (clinic_id, patient_id, appointment_id, document_type, issued_on, content, sha256, created_by) '
         'VALUES (%L, %L, %L, ''PRESCRIPTION'', DATE ''2031-07-01'', %L, repeat(''a'', 64), %L)', :'ca', :'pa2', :'apA', pg_temp.b64(), :'ua_d'));
SELECT pg_temp.expect_err('T127 paciente inexistente (FK composta)', '23503',
  pg_temp.cdoc_raw_sql(:'ca', 'f6000000-0000-4000-8000-0000000000ff', :'ua_d', 'PRESCRIPTION', 'NULL', 'NULL', quote_literal(pg_temp.b64())));

-- numero enviado pelo cliente é ignorado: 99 não aparece
SELECT pg_temp.expect_rows('T127 numero enviado pelo cliente',
  format('INSERT INTO core.clinical_documents (clinic_id, patient_id, document_type, number, issued_on, content, sha256, created_by) '
         'VALUES (%L, %L, ''PRESCRIPTION'', 99, DATE ''2031-07-01'', %L, repeat(''a'', 64), %L)', :'ca', :'pa', pg_temp.b64(), :'ua_d'), 1);
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.clinical_documents WHERE number = 99) THEN RAISE EXCEPTION 'T127: o numero enviado pelo cliente foi aceito'; END IF;
  IF (SELECT number FROM core.clinical_documents WHERE id = 'f6000000-0000-4000-8000-000000000001') <> 1 THEN RAISE EXCEPTION 'T127: o atestado deveria ter o numero 1'; END IF;
END $$;

-- 128: guardas do INSERT
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T128 paciente anonimizado', 'CR003', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa_anon', :'ua_d', 'PRESCRIPTION'));
SELECT pg_temp.expect_err('T128 paciente inativo', 'CR005', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa_off', :'ua_d', 'PRESCRIPTION'));
-- clínica alheia (paciente e usuário de A com clinic_id de B): a RLS recusa
SELECT pg_temp.expect_err('T128 clinic_id alheio', '42501', pg_temp.cdoc_sql(gen_random_uuid()::text, :'cb', :'pb', :'ub_d', 'PRESCRIPTION'));
-- autoria falsa: sessão do usuário de B, created_by do dentista de A com CRO (a guarda passa e o WITH CHECK recusa)
:ctx_a_d_user_b \gset
SELECT pg_temp.expect_err('T128 created_by diferente de app.user_id', '42501', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', 'PRESCRIPTION'));

-- recepção: o 42501 vem ANTES do CR012 (a recepcionista não tem CRO)
:ctx_a_r \gset
SELECT pg_temp.expect_err('T128 recepcao emite (42501 antes de CR012)', '42501', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_r', 'PRESCRIPTION'));
SELECT pg_temp.expect_err('T128 recepcao emite com created_by do dentista', '42501', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', 'PRESCRIPTION'));
SELECT pg_temp.expect_rows('T128 recepcao nao le documentos', 'SELECT 1 FROM core.clinical_documents', 0);
SELECT pg_temp.expect_rows('T128 recepcao nao anula', pg_temp.cdoc_void_sql(:'d1', :'ua_r'), 0);
:ctx_a_d \gset
SELECT pg_temp.expect_n('T128 dentista le os documentos', (SELECT count(*) FROM core.clinical_documents), 7);
RESET SESSION AUTHORIZATION;

-- emissor sem CRO: o dentista perde o CRO (pela própria sessão) e a emissão é recusada com CR012
:ctx_a_d \gset
UPDATE core.users SET cro_number = NULL, cro_state = NULL WHERE id = :'ua_d';
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T128 emissor sem CRO', 'CR012', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', 'PRESCRIPTION'));
RESET SESSION AUTHORIZATION;
:ctx_a_d \gset
UPDATE core.users SET cro_number = '12345', cro_state = 'SP' WHERE id = :'ua_d';
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T128 com o CRO restaurado a emissao volta a passar', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', 'PRESCRIPTION'), 1);
RESET SESSION AUTHORIZATION;

ROLLBACK;
\o
SELECT 'clinical_documents: OK' AS resultado;
