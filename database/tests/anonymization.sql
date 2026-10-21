-- =====================================================================
-- tests/anonymization.sql | Fase 1 / F06-patient-anonymization
-- Testes 46 a 51 (+ 55 parte, 52 e 53 parte) da spec global da Fase 1: quem pode anonimizar, retenção de 20
-- anos calculada a partir do último registro clínico, paciente sem prontuário, efeitos da anonimização, estado
-- terminal (CR003) e proteção de anonymized_at.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos: 42501 papel; 28000 sessão; P0002 inexistente; CR001 ativo; CR002 retenção; CR003 já anonimizado.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase1_seed.sql

\set p_active  'f1000000-0000-4000-8000-0000000000c1'
\set p_recent  'f1000000-0000-4000-8000-0000000000c2'
\set p_edge    'f1000000-0000-4000-8000-0000000000c3'
\set p_old     'f1000000-0000-4000-8000-0000000000c4'
\set p_none    'f1000000-0000-4000-8000-0000000000c5'
\set p_big     'f1000000-0000-4000-8000-0000000000c6'
\set p_b       'f1000000-0000-4000-8000-0000000000d1'
\set p_edge_ok 'f1000000-0000-4000-8000-0000000000c7'
\set f_old     'f1000000-0000-4000-8000-0000000000e1'
\set f_old2    'f1000000-0000-4000-8000-0000000000e2'

CREATE FUNCTION pg_temp.anon_sql(p_patient text) RETURNS text LANGUAGE sql AS $$
  SELECT 'SELECT core.anonymize_patient(' || quote_literal(p_patient) || ')' $$;

-- ---------------------------------------------------------------------
-- Fixtures como administrador (clínica A), com datas antigas gravadas já no INSERT
-- ---------------------------------------------------------------------
:ctx_a_d \gset
INSERT INTO core.patients (id, clinic_id, name, cpf, birth_date, sex, profession, phone, email, address, insurance_name,
                           insurance_number, acquaintance_name, acquaintance_phone, active, deactivated_at, created_by) VALUES
  (:'p_active',  :'ca', 'Paciente Ativo',   '10000000001', '1980-01-01', 'FEMALE', 'Professora', '11999990001', 'ativo@exemplo.com', 'Rua A, 1', 'Odonto X', '111', 'Maria', '11988880001', true,  NULL,  :'ua_d'),
  (:'p_recent',  :'ca', 'Paciente Recente', '10000000002', '1981-02-02', 'MALE',   'Pedreiro',   '11999990002', 'recente@exemplo.com', 'Rua B, 2', NULL, NULL, NULL, NULL, false, now(), :'ua_d'),
  (:'p_edge',    :'ca', 'Paciente Limite',  '10000000003', '1982-03-03', 'OTHER',  'Contador',   '11999990003', NULL, NULL, NULL, NULL, NULL, NULL, false, now(), :'ua_d'),
  (:'p_edge_ok', :'ca', 'Paciente Limite Ok','10000000007', '1983-04-04', 'MALE',  'Padeiro',    '11999990007', NULL, NULL, NULL, NULL, NULL, NULL, false, now(), :'ua_d'),
  (:'p_old',     :'ca', 'Paciente Antigo',  '10000000004', '1950-06-15', 'FEMALE', 'Aposentada', '11999990004', 'antigo@exemplo.com', 'Rua D, 4', 'Plano Y', '999', 'Joao', '11988880004', false, now(), :'ua_d'),
  (:'p_none',    :'ca', 'Paciente Sem Prontuario', '10000000005', '1990-09-09', 'MALE', 'Engenheiro', '11999990005', 'sem@exemplo.com', 'Rua E, 5', NULL, NULL, 'Ana', '11988880005', false, now(), :'ua_d'),
  (:'p_big',     :'ca', 'Paciente Grande',  '10000000006', '1970-10-10', 'FEMALE', 'Medica',     '11999990006', NULL, NULL, NULL, NULL, NULL, NULL, false, now(), :'ua_d');

-- recente: histórico e nota agora -> retenção não vencida
INSERT INTO core.medical_histories (clinic_id, patient_id, content, created_by) VALUES (:'ca', :'p_recent', repeat('QUJD', 12), :'ua_d');
INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by) VALUES (:'ca', :'p_recent', repeat('QUJD', 12), :'ua_d');
-- limite: último registro há 20 anos MENOS 1 dia -> ainda na retenção
INSERT INTO core.medical_histories (clinic_id, patient_id, content, created_by, created_at, updated_at)
  VALUES (:'ca', :'p_edge', repeat('QUJD', 12), :'ua_d', now() - interval '20 years' + interval '1 day', now() - interval '20 years' + interval '1 day');
-- limite aprovado: último registro há 20 anos MAIS 1 dia -> liberado
INSERT INTO core.medical_histories (clinic_id, patient_id, content, created_by, created_at, updated_at)
  VALUES (:'ca', :'p_edge_ok', repeat('QUJD', 12), :'ua_d', now() - interval '20 years' - interval '1 day', now() - interval '20 years' - interval '1 day');
-- antigo: tudo há 21 anos (histórico, nota, exames)
INSERT INTO core.medical_histories (clinic_id, patient_id, content, created_by, created_at, updated_at)
  VALUES (:'ca', :'p_old', repeat('QUJD', 12), :'ua_d', now() - interval '21 years', now() - interval '21 years');
INSERT INTO core.clinical_notes (clinic_id, patient_id, noted_at, content, created_by, created_at, updated_at)
  VALUES (:'ca', :'p_old', now() - interval '21 years', repeat('QUJD', 12), :'ua_d', now() - interval '21 years', now() - interval '21 years');
INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by, created_at, updated_at) VALUES
  (:'f_old',  :'ca', :'p_old', 'RADIOGRAPH',      :'ca' || '/' || :'p_old' || '/' || :'f_old',  'joao-silva-panoramica.png', 'image/png', 1000, :'ua_d', now() - interval '21 years', now() - interval '21 years'),
  (:'f_old2', :'ca', :'p_old', 'INTRAORAL_PHOTO', :'ca' || '/' || :'p_old' || '/' || :'f_old2', 'foto.jpg',                  'image/jpeg', 500, :'ua_d', now() - interval '21 years', now() - interval '21 years');
-- grande: 500 notas antigas
INSERT INTO core.clinical_notes (clinic_id, patient_id, noted_at, content, created_by, created_at, updated_at)
  SELECT :'ca', :'p_big', now() - interval '25 years' - g * interval '1 hour', repeat('QUJD', 12), :'ua_d',
         now() - interval '25 years', now() - interval '25 years'
    FROM generate_series(1, 500) g;
-- clínica B
:ctx_b_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, active, deactivated_at, created_by)
  VALUES (:'p_b', :'cb', 'Paciente de B', '1990-01-01', '11999990009', false, now(), :'ub_d');

SET SESSION AUTHORIZATION crm_app;

-- ---------------------------------------------------------------------
-- T46: quem pode anonimizar e em que estado
-- ---------------------------------------------------------------------
:ctx_a_r \gset
SELECT pg_temp.expect_err('T46 recepcao nao anonimiza', '42501', pg_temp.anon_sql(:'p_old'));
:ctx_a_norole \gset
SELECT pg_temp.expect_err('T46 sem papel nao anonimiza', '42501', pg_temp.anon_sql(:'p_old'));
:ctx_none \gset
SELECT set_config('app.role', 'DENTIST_ADMIN', true) AS _ \gset
SELECT pg_temp.expect_err('T46 sem clinica e usuario', '28000', pg_temp.anon_sql(:'p_old'));
:ctx_a_d_nouser \gset
SELECT pg_temp.expect_err('T46 sem usuario', '28000', pg_temp.anon_sql(:'p_old'));
:ctx_a_d \gset
SELECT pg_temp.expect_err('T46 paciente de outra clinica', 'P0002', pg_temp.anon_sql(:'p_b'));
SELECT pg_temp.expect_err('T46 paciente inexistente', 'P0002', pg_temp.anon_sql('f1000000-0000-4000-8000-0000000000ff'));
SELECT pg_temp.expect_err('T46 paciente ativo', 'CR001', pg_temp.anon_sql(:'p_active'));
-- T47: retenção
SELECT pg_temp.expect_err('T47 prontuario recente', 'CR002', pg_temp.anon_sql(:'p_recent'));
SELECT pg_temp.expect_err('T47 faltando 1 dia para os 20 anos', 'CR002', pg_temp.anon_sql(:'p_edge'));

DO $$
DECLARE
  d text;
BEGIN
  BEGIN
    PERFORM core.anonymize_patient('f1000000-0000-4000-8000-0000000000c3');
    RAISE EXCEPTION 'T47: deveria falhar com CR002';
  EXCEPTION WHEN SQLSTATE 'CR002' THEN
    GET STACKED DIAGNOSTICS d = PG_EXCEPTION_DETAIL;
    IF d !~ '^liberado em [0-9]{4}-[0-9]{2}-[0-9]{2}$'
       OR substr(d, 13)::date NOT BETWEEN current_date AND current_date + 2 THEN
      RAISE EXCEPTION 'T47: DETAIL inesperado: %', d;
    END IF;
  END;
END $$;

-- nada mudou nas tentativas que falharam (como administrador)
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  IF (SELECT count(*) FROM core.medical_histories WHERE patient_id IN
        ('f1000000-0000-4000-8000-0000000000c2', 'f1000000-0000-4000-8000-0000000000c3')) <> 2
     OR (SELECT count(*) FROM core.patients WHERE anonymized_at IS NOT NULL) <> 0
     OR (SELECT name FROM core.patients WHERE id = 'f1000000-0000-4000-8000-0000000000c2') <> 'Paciente Recente' THEN
    RAISE EXCEPTION 'T46/T47: uma tentativa recusada alterou dados';
  END IF;
END $$;
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- ---------------------------------------------------------------------
-- T48: paciente sem registro clínico, inativo: anonimiza na hora
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_rows('T48 sem prontuario anonimiza', pg_temp.anon_sql(:'p_none'), 1);
-- limite aprovado: último registro há 20 anos + 1 dia
SELECT pg_temp.expect_rows('T47 apos 20 anos + 1 dia anonimiza', pg_temp.anon_sql(:'p_edge_ok'), 1);
-- T47/T49: prontuário antigo
SELECT pg_temp.expect_rows('T47 apos 21 anos anonimiza', pg_temp.anon_sql(:'p_old'), 1);
RESET SESSION AUTHORIZATION;

-- ---------------------------------------------------------------------
-- T49: efeitos
-- ---------------------------------------------------------------------
DO $$
DECLARE
  r record;
BEGIN
  SELECT * INTO r FROM core.patients WHERE id = 'f1000000-0000-4000-8000-0000000000c4';
  IF r.anonymized_at IS NULL OR r.active OR r.deactivated_at IS NULL THEN RAISE EXCEPTION 'T49: estado terminal incorreto'; END IF;
  IF r.name <> 'Paciente anonimizado' OR r.cpf IS NOT NULL OR r.email IS NOT NULL OR r.address IS NOT NULL
     OR r.profession IS NOT NULL OR r.insurance_name IS NOT NULL OR r.insurance_number IS NOT NULL
     OR r.acquaintance_name IS NOT NULL OR r.acquaintance_phone IS NOT NULL THEN
    RAISE EXCEPTION 'T49: dado pessoal permaneceu: %', r;
  END IF;
  IF r.phone <> '0000000000' OR r.sex <> 'NOT_INFORMED' THEN RAISE EXCEPTION 'T49: telefone/sexo nao foram substituidos'; END IF;
  IF r.birth_date <> DATE '1950-01-01' THEN RAISE EXCEPTION 'T49: nascimento deve virar 1 de janeiro do ano (%)', r.birth_date; END IF;
  IF r.version <> 1 THEN RAISE EXCEPTION 'T49: version deve ser incrementada (%)', r.version; END IF;
  -- prontuário apagado
  IF EXISTS (SELECT 1 FROM core.medical_histories WHERE patient_id = r.id)
     OR EXISTS (SELECT 1 FROM core.clinical_notes WHERE patient_id = r.id) THEN
    RAISE EXCEPTION 'T49: prontuario nao foi apagado';
  END IF;
  -- exames: marcados como removidos, nome substituído, chave preservada para o backend limpar o Storage
  IF (SELECT count(*) FROM core.patient_files WHERE patient_id = r.id AND deleted_at IS NOT NULL AND original_filename = 'anonimizado') <> 2
     OR (SELECT count(*) FROM core.patient_files WHERE patient_id = r.id AND storage_key LIKE 'f1000000-0000-4000-8000-00000000000a/f1000000-0000-4000-8000-0000000000c4/%') <> 2 THEN
    RAISE EXCEPTION 'T49: exames nao foram marcados como removidos com a chave preservada';
  END IF;
  -- auditoria: ANONYMIZE + DELETEs + o histórico anterior preservado
  IF (SELECT count(*) FROM core.audit_log WHERE record_id = r.id AND action = 'ANONYMIZE' AND table_name = 'patients'
         AND user_id = 'f1000000-0000-4000-8000-0000000000a1') <> 1 THEN
    RAISE EXCEPTION 'T49: faltou a linha ANONYMIZE';
  END IF;
  IF (SELECT count(*) FROM core.audit_log WHERE action = 'DELETE' AND table_name IN ('medical_histories', 'clinical_notes')
         AND clinic_id = r.clinic_id AND user_id = 'f1000000-0000-4000-8000-0000000000a1') < 2 THEN
    RAISE EXCEPTION 'T49: DELETEs do prontuario nao foram auditados';
  END IF;
  IF (SELECT count(*) FROM core.audit_log WHERE record_id = r.id AND action = 'INSERT' AND table_name = 'patients') <> 1 THEN
    RAISE EXCEPTION 'T49: o historico de auditoria anterior nao foi preservado';
  END IF;
  -- o paciente sem prontuário e o do limite aprovado também ficaram anonimizados
  IF (SELECT count(*) FROM core.patients WHERE anonymized_at IS NOT NULL
        AND id IN ('f1000000-0000-4000-8000-0000000000c5', 'f1000000-0000-4000-8000-0000000000c7', 'f1000000-0000-4000-8000-0000000000c4')) <> 3 THEN
    RAISE EXCEPTION 'T48/T47: pacientes elegiveis nao foram anonimizados';
  END IF;
  -- os outros continuam intactos
  IF (SELECT count(*) FROM core.patients WHERE anonymized_at IS NULL AND name IN ('Paciente Ativo', 'Paciente Recente', 'Paciente Limite', 'Paciente Grande')) <> 4 THEN
    RAISE EXCEPTION 'T49: paciente nao elegivel foi alterado';
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- T50: estado terminal
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T50 segunda chamada', 'CR003', pg_temp.anon_sql(:'p_old'));
SELECT pg_temp.expect_err('T50 editar paciente anonimizado', 'CR003', format('UPDATE core.patients SET profession = ''x'' WHERE id = %L', :'p_old'));
SELECT pg_temp.expect_err('T50 reativar paciente anonimizado', 'CR003', format('UPDATE core.patients SET active = true, deactivated_at = NULL WHERE id = %L', :'p_old'));
SELECT pg_temp.expect_err('T50 novo historico', 'CR003', format(
  'INSERT INTO core.medical_histories (clinic_id, patient_id, content, created_by) VALUES (%L, %L, repeat(''QUJD'', 12), %L)', :'ca', :'p_old', :'ua_d'));
SELECT pg_temp.expect_err('T50 nova nota', 'CR003', format(
  'INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by) VALUES (%L, %L, repeat(''QUJD'', 12), %L)', :'ca', :'p_old', :'ua_d'));
SELECT pg_temp.expect_err('T50 novo exame', 'CR003', format(
  'INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by) VALUES (%L, %L, %L, ''OTHER'', %L, ''x.pdf'', ''application/pdf'', 1, %L)',
  'f1000000-0000-4000-8000-0000000000e9', :'ca', :'p_old', :'ca' || '/' || :'p_old' || '/f1000000-0000-4000-8000-0000000000e9', :'ua_d'));
:ctx_a_r \gset
SELECT pg_temp.expect_err('T50 recepcao nao anexa exame a anonimizado', 'CR003', format(
  'INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by) VALUES (%L, %L, %L, ''OTHER'', %L, ''x.pdf'', ''application/pdf'', 1, %L)',
  'f1000000-0000-4000-8000-0000000000e8', :'ca', :'p_old', :'ca' || '/' || :'p_old' || '/f1000000-0000-4000-8000-0000000000e8', :'ua_r'));
:ctx_a_d \gset

-- ---------------------------------------------------------------------
-- T50 (concorrência): o trigger dos filhos trava a linha do paciente (FOR KEY SHARE) e espera uma anonimização
-- em curso. Uma corrida real exige duas sessões com dados commitados; aqui se garante o bloqueio no catálogo e
-- que o INSERT legítimo continua funcionando com o lock (dentista e recepção).
-- ---------------------------------------------------------------------
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  IF (SELECT p.prosrc FROM pg_proc p WHERE p.oid = 'core.forbid_child_of_anonymized()'::regprocedure) !~* 'FOR KEY SHARE' THEN
    RAISE EXCEPTION 'T50: forbid_child_of_anonymized deve travar o paciente com FOR KEY SHARE (corrida com a anonimizacao)';
  END IF;
  IF (SELECT p.prosrc FROM pg_proc p WHERE p.oid = 'core.anonymize_patient(uuid)'::regprocedure) !~* 'FOR UPDATE' THEN
    RAISE EXCEPTION 'T50: anonymize_patient deve travar o paciente com FOR UPDATE';
  END IF;
END $$;
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T50 dentista insere nota para paciente ativo com o lock', format(
  'INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by) VALUES (%L, %L, repeat(''QUJD'', 12), %L)', :'ca', :'p_active', :'ua_d'), 1);
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T50 recepcao anexa exame a paciente ativo com o lock', format(
  'INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by) VALUES (%L, %L, %L, ''OTHER'', %L, ''x.pdf'', ''application/pdf'', 1, %L)',
  'f1000000-0000-4000-8000-0000000000e7', :'ca', :'p_active', :'ca' || '/' || :'p_active' || '/f1000000-0000-4000-8000-0000000000e7', :'ua_r'), 1);
:ctx_a_d \gset

-- ---------------------------------------------------------------------
-- T51: anonymized_at só pela função
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T51 UPDATE direto de anonymized_at', '42501', format('UPDATE core.patients SET anonymized_at = now() WHERE id = %L', :'p_none'));
SELECT set_config('core.anonymizing', 'on', true) AS _ \gset
SELECT pg_temp.expect_err('T51 mesmo com a flag, sem grant por coluna', '42501', format('UPDATE core.patients SET anonymized_at = now() WHERE id = %L', :'p_recent'));
SELECT pg_temp.expect_err('T51 INSERT com anonymized_at', '42501', format(
  'INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by, anonymized_at) VALUES (gen_random_uuid(), %L, ''Fulano Silva'', ''1990-01-01'', ''11999990003'', %L, now())', :'ca', :'ua_d'));
SELECT set_config('core.anonymizing', 'off', true) AS _ \gset
SELECT pg_temp.expect_err('T51 crm_app nao executa as funcoes de guarda', '42501', 'SELECT core.guard_anonymized_patient()');

-- ---------------------------------------------------------------------
-- T55 (parte): paciente com 500 notas anonimiza bem abaixo do statement_timeout de 15 s
-- ---------------------------------------------------------------------
DO $$
DECLARE
  t0 timestamptz := clock_timestamp();
  elapsed interval;
BEGIN
  PERFORM core.anonymize_patient('f1000000-0000-4000-8000-0000000000c6');
  elapsed := clock_timestamp() - t0;
  IF elapsed > interval '15 seconds' THEN
    RAISE EXCEPTION 'T55: anonimizar 500 notas levou % (limite 15 s)', elapsed;
  END IF;
  IF EXISTS (SELECT 1 FROM core.clinical_notes WHERE patient_id = 'f1000000-0000-4000-8000-0000000000c6') THEN
    RAISE EXCEPTION 'T55: notas do paciente grande nao foram apagadas';
  END IF;
END $$;

-- outra clínica: o dentista de B não enxerga (nem anonimiza) pacientes de A
:ctx_b_d \gset
SELECT pg_temp.expect_err('T46 dentista de B nao alcanca paciente de A', 'P0002', pg_temp.anon_sql(:'p_edge_ok'));
RESET SESSION AUTHORIZATION;

ROLLBACK;
\o
SELECT 'anonymization: OK' AS resultado;
