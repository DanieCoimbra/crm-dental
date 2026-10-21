-- =====================================================================
-- tests/audit_log.sql | Fase 1 / F05-audit-trail
-- Testes 43, 44 e 45 (+ 32 e 29 em audit_log) da spec global da Fase 1: cada INSERT/UPDATE/DELETE clínico gera
-- exatamente 1 linha de auditoria (sem valores); sem app.user_id a operação falha por inteiro; o log só cresce
-- (crm_app não escreve; nem o superusuário altera ou apaga); leitura só do dentista da própria clínica.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase1_seed.sql

\set pa1 'f1000000-0000-4000-8000-0000000000c1'
\set pa2 'f1000000-0000-4000-8000-0000000000c2'
\set pb1 'f1000000-0000-4000-8000-0000000000d1'
\set na1 'f1000000-0000-4000-8000-0000000000e2'
\set ha2 'f1000000-0000-4000-8000-0000000000e1'
\set fa1 'f1000000-0000-4000-8000-0000000000e3'
\set fa2 'f1000000-0000-4000-8000-0000000000e4'

-- Linhas de auditoria de um registro (ação e usuário opcionais)
CREATE FUNCTION pg_temp.audit_sql(p_record text, p_action text DEFAULT NULL, p_user text DEFAULT NULL) RETURNS text LANGUAGE sql AS $$
  SELECT 'SELECT 1 FROM core.audit_log WHERE record_id = ' || quote_literal(p_record)
         || CASE WHEN p_action IS NULL THEN '' ELSE ' AND action = ' || quote_literal(p_action) END
         || CASE WHEN p_user IS NULL THEN '' ELSE ' AND user_id = ' || quote_literal(p_user) END $$;

-- Fixtures como administrador: pb1 na clínica B
:ctx_b_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES (:'pb1', :'cb', 'Carla Dias', '1992-02-02', '11999990004', :'ub_d');

SET SESSION AUTHORIZATION crm_app;

-- ---------------------------------------------------------------------
-- T43: 1 linha por operação, com tabela, registro, ação e autor
-- ---------------------------------------------------------------------
:ctx_a_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES (:'pa1', :'ca', 'Ana Souza', '1990-05-10', '11999990001', :'ua_d');
SELECT pg_temp.expect_rows('T43 INSERT de paciente gera 1 linha', pg_temp.audit_sql(:'pa1'), 1);
SELECT pg_temp.expect_rows('T43 a linha traz INSERT, a tabela e o autor', format(
  'SELECT 1 FROM core.audit_log WHERE record_id = %L AND action = ''INSERT'' AND table_name = ''patients'' AND user_id = %L AND clinic_id = %L AND occurred_at IS NOT NULL',
  :'pa1', :'ua_d', :'ca'), 1);
UPDATE core.patients SET profession = 'Professora' WHERE id = :'pa1';
SELECT pg_temp.expect_rows('T43 UPDATE de paciente gera +1 linha', pg_temp.audit_sql(:'pa1', 'UPDATE', :'ua_d'), 1);
SELECT pg_temp.expect_rows('T43 total do paciente = 2', pg_temp.audit_sql(:'pa1'), 2);
-- UPDATE sem mudança de valores também registra (1 linha por linha tocada)
UPDATE core.patients SET profession = 'Professora' WHERE id = :'pa1';
SELECT pg_temp.expect_rows('T43 UPDATE idempotente tambem registra', pg_temp.audit_sql(:'pa1', 'UPDATE'), 2);

INSERT INTO core.medical_histories (id, clinic_id, patient_id, content, created_by) VALUES (:'ha2', :'ca', :'pa1', repeat('QUJD', 12), :'ua_d');
SELECT pg_temp.expect_rows('T43 INSERT de historico', format('SELECT 1 FROM core.audit_log WHERE record_id = %L AND table_name = ''medical_histories'' AND action = ''INSERT''', :'ha2'), 1);
INSERT INTO core.clinical_notes (id, clinic_id, patient_id, content, created_by) VALUES (:'na1', :'ca', :'pa1', repeat('QUJD', 12), :'ua_d');
SELECT pg_temp.expect_rows('T43 INSERT de nota', format('SELECT 1 FROM core.audit_log WHERE record_id = %L AND table_name = ''clinical_notes'' AND action = ''INSERT''', :'na1'), 1);
UPDATE core.clinical_notes SET content = repeat('QUJE', 12) WHERE id = :'na1';
SELECT pg_temp.expect_rows('T43 UPDATE de nota', format('SELECT 1 FROM core.audit_log WHERE record_id = %L AND table_name = ''clinical_notes'' AND action = ''UPDATE''', :'na1'), 1);
INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by)
  VALUES (:'fa1', :'ca', :'pa1', 'RADIOGRAPH', :'ca' || '/' || :'pa1' || '/' || :'fa1', 'rx.png', 'image/png', 1000, :'ua_d');
SELECT pg_temp.expect_rows('T43 INSERT de exame', format('SELECT 1 FROM core.audit_log WHERE record_id = %L AND table_name = ''patient_files'' AND action = ''INSERT''', :'fa1'), 1);

-- exame inserido pela recepção: o autor da auditoria é a recepcionista
:ctx_a_r \gset
INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by)
  VALUES (:'fa2', :'ca', :'pa1', 'OTHER', :'ca' || '/' || :'pa1' || '/' || :'fa2', 'doc.pdf', 'application/pdf', 2000, :'ua_r');
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T43 exame da recepcao auditado com o autor correto', pg_temp.audit_sql(:'fa2', 'INSERT', :'ua_r'), 1);

-- ---------------------------------------------------------------------
-- T44: sem app.user_id (ou com usuário de outra clínica) a operação clínica falha e nada persiste
-- ---------------------------------------------------------------------
:ctx_a_d_nouser \gset
SELECT pg_temp.expect_err('T44 UPDATE sem app.user_id', '28000', format('UPDATE core.patients SET profession = ''Alterada'' WHERE id = %L', :'pa1'));
SELECT pg_temp.expect_err('T44 UPDATE de nota sem app.user_id', '28000', format('UPDATE core.clinical_notes SET content = repeat(''QUJF'', 12) WHERE id = %L', :'na1'));
:ctx_a_d_user_b \gset
SELECT pg_temp.expect_err('T44 UPDATE com usuario de outra clinica', '23503', format('UPDATE core.patients SET profession = ''Alterada'' WHERE id = %L', :'pa1'));
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T44 a alteracao nao persistiu', format('SELECT 1 FROM core.patients WHERE id = %L AND profession = ''Professora''', :'pa1'), 1);
SELECT pg_temp.expect_rows('T44 nenhuma linha extra de auditoria', pg_temp.audit_sql(:'pa1'), 3);

-- ---------------------------------------------------------------------
-- T32 / T29 / T30: leitura só do dentista da própria clínica
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_rows('T29 dentista de A nao ve auditoria de B', format('SELECT 1 FROM core.audit_log WHERE clinic_id = %L', :'cb'), 0);
SELECT pg_temp.expect_rows('T29 dentista de A ve a de A', format('SELECT 1 FROM core.audit_log WHERE clinic_id = %L', :'ca'), 8);
:ctx_b_d \gset
SELECT pg_temp.expect_rows('T29 dentista de B ve so a de B', 'SELECT 1 FROM core.audit_log', 1);
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T32 recepcao nao le auditoria', 'SELECT 1 FROM core.audit_log', 0);
:ctx_none \gset
SELECT pg_temp.expect_rows('T30 sem tenant nao le auditoria', 'SELECT 1 FROM core.audit_log', 0);
:ctx_a_norole \gset
SELECT pg_temp.expect_rows('T35 sem papel nao le auditoria', 'SELECT 1 FROM core.audit_log', 0);

-- ---------------------------------------------------------------------
-- T45: o log só cresce
-- ---------------------------------------------------------------------
:ctx_a_d \gset
SELECT pg_temp.expect_err('T45 crm_app INSERT', '42501', format('INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action) VALUES (%L, %L, ''patients'', gen_random_uuid(), ''INSERT'')', :'ca', :'ua_d'));
SELECT pg_temp.expect_err('T45 crm_app UPDATE', '42501', 'UPDATE core.audit_log SET action = ''DELETE''');
SELECT pg_temp.expect_err('T45 crm_app DELETE', '42501', 'DELETE FROM core.audit_log');
SELECT pg_temp.expect_err('T45 crm_app TRUNCATE', '42501', 'TRUNCATE core.audit_log');
RESET SESSION AUTHORIZATION;

-- superusuário (ignora RLS) também não altera nem apaga: trigger de append-only
SELECT pg_temp.expect_err('T45 UPDATE pelo superusuario', '42501', 'UPDATE core.audit_log SET action = ''DELETE''');
SELECT pg_temp.expect_err('T45 DELETE pelo superusuario', '42501', 'DELETE FROM core.audit_log');
SELECT pg_temp.expect_err('T45 TRUNCATE pelo superusuario', '42501', 'TRUNCATE core.audit_log');
SELECT pg_temp.expect_err('T45 TRUNCATE ... CASCADE a partir de clinics', '42501', 'TRUNCATE core.clinics CASCADE');

-- crm_definer só escreve para a clínica da sessão e não lê
SET SESSION AUTHORIZATION crm_definer;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T45 definer nao grava para outra clinica', '42501', format(
  'INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action) VALUES (%L, %L, ''patients'', gen_random_uuid(), ''INSERT'')', :'cb', :'ub_d'));
SELECT pg_temp.expect_err('T45 definer nao le o log', '42501', 'SELECT 1 FROM core.audit_log');
RESET SESSION AUTHORIZATION;

-- ---------------------------------------------------------------------
-- T43 (DELETE): como o crm_app não apaga, o administrador apaga a nota e o trigger registra
-- ---------------------------------------------------------------------
:ctx_a_d \gset
DELETE FROM core.clinical_notes WHERE id = :'na1';
SELECT pg_temp.expect_rows('T43 DELETE de nota gera 1 linha', format('SELECT 1 FROM core.audit_log WHERE record_id = %L AND action = ''DELETE'' AND user_id = %L', :'na1', :'ua_d'), 1);
-- sem app.user_id nem o DELETE do administrador passa
:ctx_a_d_nouser \gset
SELECT pg_temp.expect_err('T44 DELETE sem app.user_id', '28000', format('DELETE FROM core.medical_histories WHERE id = %L', :'ha2'));
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T44 o historico continua la', format('SELECT 1 FROM core.medical_histories WHERE id = %L', :'ha2'), 1);

-- o log não tem coluna de valores: só identificadores
DO $$
BEGIN
  IF (SELECT array_agg(column_name::text ORDER BY column_name) FROM information_schema.columns
       WHERE table_schema = 'core' AND table_name = 'audit_log')
     IS DISTINCT FROM ARRAY['action', 'clinic_id', 'id', 'occurred_at', 'record_id', 'table_name', 'user_id'] THEN
    RAISE EXCEPTION 'T43: audit_log tem colunas fora do contrato';
  END IF;
  -- e nenhum nome, CPF ou conteudo clinico foi parar nos identificadores
  IF EXISTS (SELECT 1 FROM core.audit_log a
              WHERE a.table_name NOT IN ('patients', 'medical_histories', 'clinical_notes', 'patient_files')
                 OR a.action NOT IN ('INSERT', 'UPDATE', 'DELETE', 'ANONYMIZE')) THEN
    RAISE EXCEPTION 'T43: valores inesperados em audit_log';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'audit_log: OK' AS resultado;
