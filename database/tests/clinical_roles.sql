-- =====================================================================
-- tests/clinical_roles.sql | Fase 1 / F03-clinical-records e F04-patient-files
-- Testes 29, 30, 31 (prontuário e exames), 32, 33, 35 e 36 da spec global da Fase 1: isolamento por clínica,
-- sem tenant, FK composta, papéis (a recepção não lê prontuário nem exames e só INSERE exames), papel ausente
-- ou inválido, autoria falsa e ausência de DELETE.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase1_seed.sql

\set pa1 'f1000000-0000-4000-8000-0000000000c1'
\set pa2 'f1000000-0000-4000-8000-0000000000c2'
\set pb1 'f1000000-0000-4000-8000-0000000000d1'
\set pb2 'f1000000-0000-4000-8000-0000000000d2'
\set ha1 'f1000000-0000-4000-8000-0000000000e1'
\set na1 'f1000000-0000-4000-8000-0000000000e2'
\set fa1 'f1000000-0000-4000-8000-0000000000e3'
\set hb1 'f1000000-0000-4000-8000-0000000000f1'
\set nb1 'f1000000-0000-4000-8000-0000000000f2'
\set fb1 'f1000000-0000-4000-8000-0000000000f3'
\set fa2 'f1000000-0000-4000-8000-0000000000e4'
\set fa3 'f1000000-0000-4000-8000-0000000000e5'

-- Fixtures como administrador (ignora a RLS; a auditoria exige o contexto)
:ctx_a_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES
  (:'pa1', :'ca', 'Ana Souza',  '1990-05-10', '11999990001', :'ua_d'),
  (:'pa2', :'ca', 'Bruno Lima', '1985-01-01', '11999990002', :'ua_d');
INSERT INTO core.medical_histories (id, clinic_id, patient_id, content, created_by)
  VALUES (:'ha1', :'ca', :'pa1', repeat('QUJD', 12), :'ua_d');
INSERT INTO core.clinical_notes (id, clinic_id, patient_id, content, created_by)
  VALUES (:'na1', :'ca', :'pa1', repeat('QUJD', 12), :'ua_d');
INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by)
  VALUES (:'fa1', :'ca', :'pa1', 'RADIOGRAPH', :'ca' || '/' || :'pa1' || '/' || :'fa1', 'rx.png', 'image/png', 1000, :'ua_d');
:ctx_b_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by)
  VALUES (:'pb1', :'cb', 'Carla Dias', '1992-02-02', '11999990004', :'ub_d');
-- paciente de B SEM histórico: o teste da FK composta não pode esbarrar no UNIQUE (patient_id)
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by)
  VALUES (:'pb2', :'cb', 'Diego Neves', '1993-03-03', '11999990010', :'ub_d');
INSERT INTO core.medical_histories (id, clinic_id, patient_id, content, created_by)
  VALUES (:'hb1', :'cb', :'pb1', repeat('QUJD', 12), :'ub_d');
INSERT INTO core.clinical_notes (id, clinic_id, patient_id, content, created_by)
  VALUES (:'nb1', :'cb', :'pb1', repeat('QUJD', 12), :'ub_d');
INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by)
  VALUES (:'fb1', :'cb', :'pb1', 'RADIOGRAPH', :'cb' || '/' || :'pb1' || '/' || :'fb1', 'rx.png', 'image/png', 1000, :'ub_d');

-- Corpos de INSERT reutilizáveis (clínica A, paciente pa2, autoria informada)
CREATE FUNCTION pg_temp.history_sql(p_patient uuid, p_created_by uuid, p_clinic uuid) RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.medical_histories (id, clinic_id, patient_id, content, created_by) VALUES (gen_random_uuid(), %L, %L, repeat(''QUJD'', 12), %L)',
                p_clinic, p_patient, p_created_by) $$;
CREATE FUNCTION pg_temp.note_sql(p_patient uuid, p_created_by uuid, p_clinic uuid) RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.clinical_notes (id, clinic_id, patient_id, content, created_by) VALUES (gen_random_uuid(), %L, %L, repeat(''QUJD'', 12), %L)',
                p_clinic, p_patient, p_created_by) $$;
CREATE FUNCTION pg_temp.file_sql(p_id uuid, p_patient uuid, p_created_by uuid, p_clinic uuid, p_returning boolean DEFAULT false) RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by) '
                || 'VALUES (%L, %L, %L, ''OTHER'', %L, ''doc.pdf'', ''application/pdf'', 2000, %L)',
                p_id, p_clinic, p_patient, p_clinic::text || '/' || p_patient::text || '/' || p_id::text, p_created_by)
         || CASE WHEN p_returning THEN ' RETURNING id' ELSE '' END $$;

SET SESSION AUTHORIZATION crm_app;

-- ---------------------------------------------------------------------
-- T29 / T32: dentista de A lê o próprio prontuário e exames, nada de B; não apaga
-- ---------------------------------------------------------------------
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T29 historicos de A', 'SELECT 1 FROM core.medical_histories', 1);
SELECT pg_temp.expect_rows('T29 notas de A', 'SELECT 1 FROM core.clinical_notes', 1);
SELECT pg_temp.expect_rows('T29 exames de A', 'SELECT 1 FROM core.patient_files', 1);
SELECT pg_temp.expect_rows('T29 auditoria nao traz a clinica B', format('SELECT 1 FROM core.audit_log WHERE clinic_id = %L', :'cb'), 0);
SELECT pg_temp.expect_rows('T29 UPDATE historico de B afeta 0', format('UPDATE core.medical_histories SET content = repeat(''QUJE'', 12) WHERE id = %L', :'hb1'), 0);
SELECT pg_temp.expect_rows('T29 UPDATE nota de B afeta 0', format('UPDATE core.clinical_notes SET content = repeat(''QUJE'', 12) WHERE id = %L', :'nb1'), 0);
SELECT pg_temp.expect_rows('T29 UPDATE exame de B afeta 0', format('UPDATE core.patient_files SET kind = ''OTHER'' WHERE id = %L', :'fb1'), 0);
SELECT pg_temp.expect_err('T52 DELETE historico', '42501', 'DELETE FROM core.medical_histories');
SELECT pg_temp.expect_err('T52 DELETE nota', '42501', 'DELETE FROM core.clinical_notes');
SELECT pg_temp.expect_err('T52 DELETE exame', '42501', 'DELETE FROM core.patient_files');
SELECT pg_temp.expect_err('T52 TRUNCATE nota', '42501', 'TRUNCATE core.clinical_notes');
SELECT pg_temp.expect_err('T29 INSERT historico com clinic_id de B', '42501', pg_temp.history_sql(:'pa2', :'ua_d', :'cb'));
SELECT pg_temp.expect_err('T29 INSERT nota com clinic_id de B', '42501', pg_temp.note_sql(:'pa2', :'ua_d', :'cb'));
SELECT pg_temp.expect_err('T29 INSERT exame com clinic_id de B', '42501', pg_temp.file_sql(:'fa2', :'pa2', :'ua_d', :'cb'));
-- T31: paciente de outra clínica (FK composta)
SELECT pg_temp.expect_err('T31 historico de paciente de B', '23503', pg_temp.history_sql(:'pb2', :'ua_d', :'ca'));
SELECT pg_temp.expect_err('T31 nota de paciente de B', '23503', pg_temp.note_sql(:'pb1', :'ua_d', :'ca'));
-- (a chave do exame segue o padrão da clínica A e do paciente de B: só a FK composta (patient_id, clinic_id) falha)
SELECT pg_temp.expect_err('T31 exame de paciente de B', '23503', format(
  'INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by) '
  || 'VALUES (gen_random_uuid(), %L, %L, ''OTHER'', %L, ''doc.pdf'', ''application/pdf'', 2000, %L)',
  :'ca', :'pb1', :'ca' || '/' || :'pb1' || '/' || 'f1000000-0000-4000-8000-0000000000aa', :'ua_d'));
-- operações permitidas ao dentista
SELECT pg_temp.expect_rows('dentista cria historico', pg_temp.history_sql(:'pa2', :'ua_d', :'ca'), 1);
SELECT pg_temp.expect_rows('dentista cria nota', pg_temp.note_sql(:'pa1', :'ua_d', :'ca'), 1);
SELECT pg_temp.expect_rows('dentista cria exame', pg_temp.file_sql(:'fa2', :'pa1', :'ua_d', :'ca'), 1);
SELECT pg_temp.expect_rows('dentista edita nota', format('UPDATE core.clinical_notes SET content = repeat(''QUJE'', 12) WHERE id = %L', :'na1'), 1);
SELECT pg_temp.expect_rows('dentista remove exame (logico)', format('UPDATE core.patient_files SET deleted_at = now() WHERE id = %L', :'fa2'), 1);
SELECT pg_temp.expect_rows('dentista le o exame removido logicamente', format('SELECT 1 FROM core.patient_files WHERE id = %L', :'fa2'), 1);

-- T36: autoria falsa
SELECT pg_temp.expect_err('T36 historico com created_by falso', '42501', pg_temp.history_sql(:'pa1', :'ua_r', :'ca'));
SELECT pg_temp.expect_err('T36 nota com created_by falso', '42501', pg_temp.note_sql(:'pa1', :'ua_r', :'ca'));
SELECT pg_temp.expect_err('T36 exame com created_by falso', '42501', pg_temp.file_sql(gen_random_uuid(), :'pa1', :'ua_r', :'ca'));

-- ---------------------------------------------------------------------
-- T30: sem nenhuma variável de sessão
-- ---------------------------------------------------------------------
:ctx_none \gset
SELECT pg_temp.expect_rows('T30 historicos', 'SELECT 1 FROM core.medical_histories', 0);
SELECT pg_temp.expect_rows('T30 notas', 'SELECT 1 FROM core.clinical_notes', 0);
SELECT pg_temp.expect_rows('T30 exames', 'SELECT 1 FROM core.patient_files', 0);
SELECT pg_temp.expect_rows('T30 auditoria', 'SELECT 1 FROM core.audit_log', 0);
SELECT pg_temp.expect_err('T30 INSERT historico', '42501', pg_temp.history_sql(:'pa1', :'ua_d', :'ca'));
SELECT pg_temp.expect_err('T30 INSERT nota', '42501', pg_temp.note_sql(:'pa1', :'ua_d', :'ca'));
SELECT pg_temp.expect_err('T30 INSERT exame', '42501', pg_temp.file_sql(gen_random_uuid(), :'pa1', :'ua_d', :'ca'));

-- ---------------------------------------------------------------------
-- T35: papel ausente ou inválido
-- ---------------------------------------------------------------------
:ctx_a_norole \gset
SELECT pg_temp.expect_rows('T35 sem papel: historicos', 'SELECT 1 FROM core.medical_histories', 0);
SELECT pg_temp.expect_rows('T35 sem papel: notas', 'SELECT 1 FROM core.clinical_notes', 0);
SELECT pg_temp.expect_rows('T35 sem papel: exames', 'SELECT 1 FROM core.patient_files', 0);
SELECT pg_temp.expect_err('T35 sem papel: INSERT nota', '42501', pg_temp.note_sql(:'pa1', :'ua_d', :'ca'));
SELECT pg_temp.expect_err('T35 sem papel: INSERT exame', '42501', pg_temp.file_sql(gen_random_uuid(), :'pa1', :'ua_d', :'ca'));
:ctx_a_d \gset
SELECT set_config('app.role', 'ADMIN', true) AS _ \gset
SELECT pg_temp.expect_rows('T35 papel ADMIN: notas', 'SELECT 1 FROM core.clinical_notes', 0);
SELECT pg_temp.expect_err('T35 papel ADMIN: INSERT historico', '42501', pg_temp.history_sql(:'pa2', :'ua_d', :'ca'));
SELECT pg_temp.expect_rows('T35 papel ADMIN: UPDATE exame afeta 0', 'UPDATE core.patient_files SET kind = ''OTHER''', 0);

-- ---------------------------------------------------------------------
-- T32 / T33: recepção
-- ---------------------------------------------------------------------
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T32 recepcao nao le historicos', 'SELECT 1 FROM core.medical_histories', 0);
SELECT pg_temp.expect_rows('T32 recepcao nao le notas', 'SELECT 1 FROM core.clinical_notes', 0);
SELECT pg_temp.expect_rows('T32 recepcao nao le a auditoria', 'SELECT 1 FROM core.audit_log', 0);
SELECT pg_temp.expect_err('T32 recepcao nao grava historico', '42501', pg_temp.history_sql(:'pa1', :'ua_r', :'ca'));
SELECT pg_temp.expect_err('T32 recepcao nao grava nota', '42501', pg_temp.note_sql(:'pa1', :'ua_r', :'ca'));
SELECT pg_temp.expect_rows('T32 recepcao nao altera historico', format('UPDATE core.medical_histories SET content = repeat(''QUJE'', 12) WHERE id = %L', :'ha1'), 0);
SELECT pg_temp.expect_rows('T32 recepcao nao altera nota', format('UPDATE core.clinical_notes SET content = repeat(''QUJE'', 12) WHERE id = %L', :'na1'), 0);
-- exames: só INSERT
SELECT pg_temp.expect_rows('T33 recepcao insere exame', pg_temp.file_sql(:'fa3', :'pa1', :'ua_r', :'ca'), 1);
SELECT pg_temp.expect_rows('T33 recepcao nao le exames', 'SELECT 1 FROM core.patient_files', 0);
SELECT pg_temp.expect_rows('T33 recepcao nao altera exame', format('UPDATE core.patient_files SET deleted_at = now() WHERE id = %L', :'fa3'), 0);
SELECT pg_temp.expect_err('T33 INSERT ... RETURNING do exame falha (sem SELECT)', '42501', pg_temp.file_sql(gen_random_uuid(), :'pa1', :'ua_r', :'ca', true));
SELECT pg_temp.expect_err('T36 recepcao com created_by do dentista', '42501', pg_temp.file_sql(gen_random_uuid(), :'pa1', :'ua_d', :'ca'));

RESET SESSION AUTHORIZATION;

-- o exame da recepção existe de fato (conferido como administrador) e é da clínica A
DO $$
BEGIN
  IF (SELECT count(*) FROM core.patient_files
       WHERE id = 'f1000000-0000-4000-8000-0000000000e5' AND clinic_id = 'f1000000-0000-4000-8000-00000000000a'
         AND created_by = 'f1000000-0000-4000-8000-0000000000a2') <> 1 THEN
    RAISE EXCEPTION 'T33: o exame inserido pela recepcao nao foi gravado como esperado';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'clinical_roles: OK' AS resultado;
