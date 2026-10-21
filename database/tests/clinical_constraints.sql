-- =====================================================================
-- tests/clinical_constraints.sql | Fase 1 / F03-clinical-records e F04-patient-files
-- Testes 38 (exames), 40, 41 e 42 (histórico, nota e exame): conteúdo cifrado opaco (formato e tamanho),
-- unicidade do histórico, domínios dos exames, chave do objeto, imutáveis, updated_at e ON DELETE RESTRICT.
-- Executa como administrador (superusuário ignora a RLS), o que ISOLA constraints e triggers das políticas.
-- Executar : depois das migrations. Tudo dentro de uma transação revertida.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase1_seed.sql

\set pa1 'f1000000-0000-4000-8000-0000000000c1'
\set pa2 'f1000000-0000-4000-8000-0000000000c2'
\set pa3 'f1000000-0000-4000-8000-0000000000c3'

:ctx_a_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES
  (:'pa1', :'ca', 'Ana Souza',  '1990-05-10', '11999990001', :'ua_d'),
  (:'pa2', :'ca', 'Bruno Lima', '1985-01-01', '11999990002', :'ua_d'),
  (:'pa3', :'ca', 'Clara Reis', '1978-07-07', '11999990003', :'ua_d');

-- INSERT de histórico / nota com o conteúdo informado (expressão SQL) para o paciente pa1
CREATE FUNCTION pg_temp.h_sql(p_content text, p_patient text DEFAULT 'f1000000-0000-4000-8000-0000000000c1') RETURNS text LANGUAGE sql AS $$
  SELECT 'INSERT INTO core.medical_histories (id, clinic_id, patient_id, content, created_by) VALUES (gen_random_uuid(), '
         || '''f1000000-0000-4000-8000-00000000000a'', ''' || p_patient || ''', ' || p_content || ', ''f1000000-0000-4000-8000-0000000000a1'')' $$;
CREATE FUNCTION pg_temp.n_sql(p_content text) RETURNS text LANGUAGE sql AS $$
  SELECT 'INSERT INTO core.clinical_notes (id, clinic_id, patient_id, content, created_by) VALUES (gen_random_uuid(), '
         || '''f1000000-0000-4000-8000-00000000000a'', ''f1000000-0000-4000-8000-0000000000c1'', ' || p_content || ', ''f1000000-0000-4000-8000-0000000000a1'')' $$;
-- INSERT de exame com campos variáveis
CREATE FUNCTION pg_temp.f_sql(p_id text, p_kind text, p_key text, p_type text, p_size text) RETURNS text LANGUAGE sql AS $$
  SELECT 'INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by) VALUES ('
         || '''' || p_id || ''', ''f1000000-0000-4000-8000-00000000000a'', ''f1000000-0000-4000-8000-0000000000c1'', ''' || p_kind || ''', '
         || quote_literal(p_key) || ', ''arquivo.bin'', ''' || p_type || ''', ' || p_size || ', ''f1000000-0000-4000-8000-0000000000a1'')' $$;

-- ---------------------------------------------------------------------
-- T40: conteúdo cifrado e opaco (histórico e nota)
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T40 texto em claro (espacos e acentos)', '23514', pg_temp.h_sql('''Paciente alergico a penicilina, hipertenso e diabetico'''));
SELECT pg_temp.expect_err('T40 nota em claro', '23514', pg_temp.n_sql('''Realizada restauracao do dente 36 sem intercorrencias, paciente tolerou bem'''));
SELECT pg_temp.expect_err('T40 conteudo curto (4)', '23514', pg_temp.h_sql('''QUJD'''));
SELECT pg_temp.expect_err('T40 conteudo curto (36)', '23514', pg_temp.h_sql('repeat(''QUJD'', 9)'));
SELECT pg_temp.expect_err('T40 vazio', '23514', pg_temp.h_sql(''''''));
SELECT pg_temp.expect_err('T40 padding no meio', '23514', pg_temp.h_sql('repeat(''QUJD'', 10) || ''=A'''));
SELECT pg_temp.expect_err('T40 Base64 URL-safe (-)', '23514', pg_temp.h_sql('repeat(''QUJD'', 10) || ''-_'''));
SELECT pg_temp.expect_err('T40 quebra de linha', '23514', pg_temp.h_sql('repeat(''QUJD'', 10) || chr(10) || ''QUJD'''));
SELECT pg_temp.expect_err('T40 mais de 3 sinais de igual', '23514', pg_temp.h_sql('repeat(''QUJD'', 10) || ''==='''));
SELECT pg_temp.expect_err('T40 historico maior que a coluna', '22001', pg_temp.h_sql('repeat(''A'', 54001)'));
SELECT pg_temp.expect_err('T40 nota maior que a coluna', '22001', pg_temp.n_sql('repeat(''A'', 27001)'));
-- mínimo (40) e padding no fim passam
SELECT pg_temp.expect_rows('T40 historico de 40 caracteres', pg_temp.h_sql('repeat(''A'', 40)'), 1);
SELECT pg_temp.expect_err('T40 segundo historico do mesmo paciente', '23505', pg_temp.h_sql('repeat(''QUJD'', 12)'));
SELECT pg_temp.expect_rows('T40 historico de outro paciente', pg_temp.h_sql('repeat(''QUJD'', 10) || ''QQ=='' ', 'f1000000-0000-4000-8000-0000000000c2'), 1);
SELECT pg_temp.expect_rows('T40 nota valida', pg_temp.n_sql('repeat(''QUJD'', 12)'), 1);
SELECT pg_temp.expect_rows('T40 varias notas por paciente', pg_temp.n_sql('repeat(''QUJE'', 12)'), 1);
SELECT pg_temp.expect_rows('T40 nota no tamanho maximo da coluna', pg_temp.n_sql('repeat(''A'', 27000)'), 1);
SELECT pg_temp.expect_rows('T40 historico no tamanho maximo da coluna', pg_temp.h_sql('repeat(''A'', 54000)', 'f1000000-0000-4000-8000-0000000000c3'), 1);

-- ---------------------------------------------------------------------
-- T38 / T41: exames
-- ---------------------------------------------------------------------
\set k_ok 'f1000000-0000-4000-8000-00000000000a/f1000000-0000-4000-8000-0000000000c1/f1000000-0000-4000-8000-0000000000e1'
SELECT pg_temp.expect_err('T38 kind invalido', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e1', 'XRAY', :'k_ok', 'image/png', '1000'));
SELECT pg_temp.expect_err('T38 tipo text/plain', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e1', 'OTHER', :'k_ok', 'text/plain', '1000'));
SELECT pg_temp.expect_err('T38 tipo image/gif', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e1', 'OTHER', :'k_ok', 'image/gif', '1000'));
SELECT pg_temp.expect_err('T38 tamanho 0', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e1', 'OTHER', :'k_ok', 'image/png', '0'));
SELECT pg_temp.expect_err('T38 tamanho negativo', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e1', 'OTHER', :'k_ok', 'image/png', '-1'));
SELECT pg_temp.expect_err('T38 tamanho 10 MB + 1', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e1', 'OTHER', :'k_ok', 'image/png', '10485761'));
SELECT pg_temp.expect_rows('T38 tamanho 10 MB exato', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e1', 'RADIOGRAPH', :'k_ok', 'image/png', '10485760'), 1);
SELECT pg_temp.expect_err('T41 chave duplicada', '23505', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e2', 'OTHER', :'k_ok', 'image/png', '1000'));
SELECT pg_temp.expect_err('T41 chave de outra clinica', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e2', 'OTHER',
  'f1000000-0000-4000-8000-00000000000b/f1000000-0000-4000-8000-0000000000c1/f1000000-0000-4000-8000-0000000000e2', 'image/png', '1000'));
SELECT pg_temp.expect_err('T41 chave de outro paciente', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e2', 'OTHER',
  'f1000000-0000-4000-8000-00000000000a/f1000000-0000-4000-8000-0000000000c2/f1000000-0000-4000-8000-0000000000e2', 'image/png', '1000'));
SELECT pg_temp.expect_err('T41 chave sem uuid do objeto', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e2', 'OTHER',
  'f1000000-0000-4000-8000-00000000000a/f1000000-0000-4000-8000-0000000000c1/radiografia.png', 'image/png', '1000'));
SELECT pg_temp.expect_err('T41 chave com caminho extra', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e2', 'OTHER',
  'f1000000-0000-4000-8000-00000000000a/f1000000-0000-4000-8000-0000000000c1/x/f1000000-0000-4000-8000-0000000000e2', 'image/png', '1000'));
SELECT pg_temp.expect_err('T41 uuid do objeto em maiusculas', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e2', 'OTHER',
  'f1000000-0000-4000-8000-00000000000a/f1000000-0000-4000-8000-0000000000c1/F1000000-0000-4000-8000-0000000000E2', 'image/png', '1000'));
SELECT pg_temp.expect_err('T41 chave sem o prefixo da clinica', '23514', pg_temp.f_sql('f1000000-0000-4000-8000-0000000000e2', 'OTHER',
  'f1000000-0000-4000-8000-0000000000c1/f1000000-0000-4000-8000-0000000000e2', 'image/png', '1000'));
SELECT pg_temp.expect_err('T38 exame sem nome do arquivo', '23502', format(
  'INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, content_type, size_bytes, created_by) VALUES (%L, %L, %L, ''OTHER'', %L, ''image/png'', 1, %L)',
  'f1000000-0000-4000-8000-0000000000e3', :'ca', :'pa1', :'ca' || '/' || :'pa1' || '/f1000000-0000-4000-8000-0000000000e3', :'ua_d'));

-- ---------------------------------------------------------------------
-- T42: imutáveis, editáveis e updated_at
-- ---------------------------------------------------------------------
\set n_id 'f1000000-0000-4000-8000-00000000aa02'
INSERT INTO core.clinical_notes (id, clinic_id, patient_id, content, created_by) VALUES (:'n_id', :'ca', :'pa2', repeat('QUJD', 12), :'ua_d');

SELECT pg_temp.expect_err('T42 historico: patient_id imutavel', '23514', format('UPDATE core.medical_histories SET patient_id = %L WHERE patient_id = %L', :'pa1', :'pa2'));
SELECT pg_temp.expect_err('T42 historico: clinic_id imutavel', '23514', format('UPDATE core.medical_histories SET clinic_id = %L WHERE patient_id = %L', :'cb', :'pa2'));
SELECT pg_temp.expect_err('T42 historico: created_by imutavel', '23514', format('UPDATE core.medical_histories SET created_by = %L WHERE patient_id = %L', :'ua_r', :'pa2'));
SELECT pg_temp.expect_err('T42 historico: id imutavel', '23514', format('UPDATE core.medical_histories SET id = gen_random_uuid() WHERE patient_id = %L', :'pa2'));
SELECT pg_temp.expect_err('T42 nota: patient_id imutavel', '23514', format('UPDATE core.clinical_notes SET patient_id = %L WHERE id = %L', :'pa1', :'n_id'));
SELECT pg_temp.expect_err('T42 nota: created_at imutavel', '23514', format('UPDATE core.clinical_notes SET created_at = now() - interval ''1 day'' WHERE id = %L', :'n_id'));
SELECT pg_temp.expect_rows('T42 nota: conteudo e noted_at sao editaveis', format('UPDATE core.clinical_notes SET content = repeat(''QUJE'', 12), noted_at = now() - interval ''1 day'' WHERE id = %L', :'n_id'), 1);

INSERT INTO core.patient_files (id, clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by)
  VALUES ('f1000000-0000-4000-8000-00000000aa03', :'ca', :'pa2', 'OTHER',
          :'ca' || '/' || :'pa2' || '/f1000000-0000-4000-8000-00000000aa03', 'a.pdf', 'application/pdf', 10, :'ua_d');
SELECT pg_temp.expect_err('T42 exame: storage_key imutavel', '23514', 'UPDATE core.patient_files SET storage_key = storage_key || ''x'' WHERE id = ''f1000000-0000-4000-8000-00000000aa03''');
SELECT pg_temp.expect_err('T42 exame: size_bytes imutavel', '23514', 'UPDATE core.patient_files SET size_bytes = 11 WHERE id = ''f1000000-0000-4000-8000-00000000aa03''');
SELECT pg_temp.expect_err('T42 exame: content_type imutavel', '23514', 'UPDATE core.patient_files SET content_type = ''image/png'' WHERE id = ''f1000000-0000-4000-8000-00000000aa03''');
SELECT pg_temp.expect_err('T42 exame: patient_id imutavel', '23514', format('UPDATE core.patient_files SET patient_id = %L WHERE id = ''f1000000-0000-4000-8000-00000000aa03''', :'pa1'));
SELECT pg_temp.expect_rows('T42 exame: kind, nome e deleted_at sao editaveis',
  'UPDATE core.patient_files SET kind = ''RADIOGRAPH'', original_filename = ''b.pdf'', deleted_at = now() WHERE id = ''f1000000-0000-4000-8000-00000000aa03''', 1);

-- updated_at nas três tabelas: recua o valor (trigger desligado só aqui) e confere que um UPDATE o refaz
DO $$
DECLARE
  t text; col text; id_col text; id_val uuid;
  cfg text[][] := ARRAY[['medical_histories', 'content', ''],
                        ['clinical_notes',    'content', 'f1000000-0000-4000-8000-00000000aa02'],
                        ['patient_files',     'kind',    'f1000000-0000-4000-8000-00000000aa03']];
  i int; v_old timestamptz := '2000-01-01T00:00:00Z'; v_new timestamptz;
BEGIN
  FOR i IN 1 .. 3 LOOP
    t := cfg[i][1]; col := cfg[i][2];
    IF cfg[i][3] = '' THEN
      SELECT h.id INTO id_val FROM core.medical_histories h WHERE h.patient_id = 'f1000000-0000-4000-8000-0000000000c2';
    ELSE
      id_val := cfg[i][3]::uuid;
    END IF;
    EXECUTE format('ALTER TABLE core.%I DISABLE TRIGGER USER', t);
    EXECUTE format('UPDATE core.%I SET updated_at = %L WHERE id = %L', t, v_old, id_val);
    EXECUTE format('ALTER TABLE core.%I ENABLE TRIGGER USER', t);
    IF t = 'patient_files' THEN
      EXECUTE format('UPDATE core.%I SET kind = ''OTHER'' WHERE id = %L', t, id_val);
    ELSE
      EXECUTE format('UPDATE core.%I SET content = repeat(''QUJF'', 12) WHERE id = %L', t, id_val);
    END IF;
    EXECUTE format('SELECT updated_at FROM core.%I WHERE id = %L', t, id_val) INTO v_new;
    IF v_new <= v_old THEN
      RAISE EXCEPTION 'T42: updated_at de % nao foi atualizado pelo trigger', t;
    END IF;
  END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- ON DELETE RESTRICT: nem o administrador apaga paciente com registros clínicos
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('FK paciente com historico nao e apagado', '23503', format('DELETE FROM core.patients WHERE id = %L', :'pa2'));
SELECT pg_temp.expect_err('FK usuario autor nao e apagado', '23503', format('DELETE FROM core.users WHERE id = %L', :'ua_d'));

ROLLBACK;
\o
SELECT 'clinical_constraints: OK' AS resultado;
