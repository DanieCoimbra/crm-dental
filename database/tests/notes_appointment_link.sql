-- =====================================================================
-- tests/notes_appointment_link.sql | Fase 2 / F05-note-appointment-link
-- Testes 75 e 60 (parte das notas) da spec global da Fase 2: vínculo opcional da nota clínica com o agendamento, por FK
-- composta (id, patient_id, clinic_id) que exige a MESMA clínica e o MESMO paciente. Aditivo: nota sem vínculo continua
-- válida; a recepção continua sem acesso a clinical_notes.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 23503 agendamento inexistente, de outra clínica ou de outro paciente; 42501 recepção.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase2_seed.sql

\set ap1 'f2000000-0000-4000-8000-000000000401'
\set ap2 'f2000000-0000-4000-8000-000000000402'
\set ap_b 'f2000000-0000-4000-8000-000000000403'
\set n1  'f2000000-0000-4000-8000-000000000411'
\set n2  'f2000000-0000-4000-8000-000000000412'
\set n3  'f2000000-0000-4000-8000-000000000413'

-- Fixtures como administrador: dois agendamentos de pa em A (ap1, ap2) e um de pb em B (ap_b)
:ctx_a_d \gset
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, created_by) VALUES
  (:'ap1', :'ca', :'pa',  '2031-06-01 08:00+00', '2031-06-01 09:00+00', :'ua_d'),
  (:'ap2', :'ca', :'pa2', '2031-06-01 09:00+00', '2031-06-01 10:00+00', :'ua_d');
-- uma nota ANTIGA (sem vínculo), como existiria antes da migration
INSERT INTO core.clinical_notes (id, clinic_id, patient_id, content, created_by) VALUES
  (:'n1', :'ca', :'pa', repeat('QUJD', 12), :'ua_d');
:ctx_b_d \gset
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, created_by) VALUES
  (:'ap_b', :'cb', :'pb', '2031-06-01 08:00+00', '2031-06-01 09:00+00', :'ub_d');

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- notas existentes intactas: appointment_id nulo
SELECT pg_temp.expect_rows('T75 nota anterior continua sem vinculo', format('SELECT 1 FROM core.clinical_notes WHERE id = %L AND appointment_id IS NULL', :'n1'), 1);
-- nota sem vínculo passa (nulo)
SELECT pg_temp.expect_rows('T75 nota nova sem vinculo', format(
  'INSERT INTO core.clinical_notes (id, clinic_id, patient_id, content, created_by) VALUES (%L, %L, %L, repeat(%L, 12), %L)',
  :'n2', :'ca', :'pa', 'QUJD', :'ua_d'), 1);
-- nota com vínculo ao agendamento do MESMO paciente
SELECT pg_temp.expect_rows('T75 nota nova vinculada ao agendamento do mesmo paciente', format(
  'INSERT INTO core.clinical_notes (id, clinic_id, patient_id, content, created_by, appointment_id) VALUES (%L, %L, %L, repeat(%L, 12), %L, %L)',
  :'n3', :'ca', :'pa', 'QUJD', :'ua_d', :'ap1'), 1);
-- várias notas no mesmo agendamento (sem UNIQUE)
SELECT pg_temp.expect_rows('T75 segunda nota no mesmo agendamento', format(
  'INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by, appointment_id) VALUES (%L, %L, repeat(%L, 12), %L, %L)',
  :'ca', :'pa', 'QUJE', :'ua_d', :'ap1'), 1);
SELECT pg_temp.expect_rows('T75 as notas do atendimento (indice parcial)', format('SELECT 1 FROM core.clinical_notes WHERE clinic_id = %L AND appointment_id = %L', :'ca', :'ap1'), 2);

-- vincular depois por UPDATE (a coluna é mutável) e trocar o vínculo
SELECT pg_temp.expect_rows('T75 vincular a nota antiga depois', format('UPDATE core.clinical_notes SET appointment_id = %L WHERE id = %L', :'ap1', :'n1'), 1);
SELECT pg_temp.expect_rows('T75 desvincular (voltar a nulo)', format('UPDATE core.clinical_notes SET appointment_id = NULL WHERE id = %L', :'n1'), 1);

-- T60/T75: agendamento de outro PACIENTE, de outra CLÍNICA ou inexistente
SELECT pg_temp.expect_err('T75 agendamento de outro paciente (insert)', '23503', format(
  'INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by, appointment_id) VALUES (%L, %L, repeat(%L, 12), %L, %L)',
  :'ca', :'pa', 'QUJD', :'ua_d', :'ap2'));
SELECT pg_temp.expect_err('T75 agendamento de outro paciente (update)', '23503', format('UPDATE core.clinical_notes SET appointment_id = %L WHERE id = %L', :'ap2', :'n1'));
SELECT pg_temp.expect_err('T60 agendamento de outra clinica (insert)', '23503', format(
  'INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by, appointment_id) VALUES (%L, %L, repeat(%L, 12), %L, %L)',
  :'ca', :'pa', 'QUJD', :'ua_d', :'ap_b'));
SELECT pg_temp.expect_err('T60 agendamento de outra clinica (update)', '23503', format('UPDATE core.clinical_notes SET appointment_id = %L WHERE id = %L', :'ap_b', :'n1'));
SELECT pg_temp.expect_err('T75 agendamento inexistente', '23503', format('UPDATE core.clinical_notes SET appointment_id = gen_random_uuid() WHERE id = %L', :'n1'));

-- a recepção continua sem acesso a clinical_notes
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T75 recepcao nao le notas', 'SELECT 1 FROM core.clinical_notes', 0);
SELECT pg_temp.expect_err('T75 recepcao nao insere nota', '42501', format(
  'INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by, appointment_id) VALUES (%L, %L, repeat(%L, 12), %L, %L)',
  :'ca', :'pa', 'QUJD', :'ua_r', :'ap1'));
SELECT pg_temp.expect_rows('T75 recepcao nao vincula nota (0 linhas)', format('UPDATE core.clinical_notes SET appointment_id = %L WHERE id = %L', :'ap1', :'n1'), 0);

-- sem DELETE de agendamento por crm_app: o RESTRICT é garantia adicional; o administrador tenta e a FK segura
RESET SESSION AUTHORIZATION;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T75 agendamento vinculado a nota nao pode ser apagado (RESTRICT)', '23503', format('DELETE FROM core.appointments WHERE id = %L', :'ap1'));

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conname = 'fk_notes_appointment' AND c.contype = 'f'
                    AND c.confrelid = 'core.appointments'::regclass AND c.confdeltype = 'r' AND array_length(c.conkey, 1) = 3) THEN
    RAISE EXCEPTION 'T75: fk_notes_appointment deve ser composta (appointment_id, patient_id, clinic_id) com ON DELETE RESTRICT';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'core' AND indexname = 'ix_notes_appointment'
                    AND indexdef LIKE '%(clinic_id, appointment_id)%' AND indexdef LIKE '%WHERE (appointment_id IS NOT NULL)%') THEN
    RAISE EXCEPTION 'T75: ix_notes_appointment deve ser parcial (clinic_id, appointment_id) WHERE appointment_id IS NOT NULL';
  END IF;
  -- sem UNIQUE: "uma nota por atendimento" é regra do backend
  IF EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = 'core.clinical_notes'::regclass AND i.indisunique
                AND i.indkey::int2[] @> ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid = 'core.clinical_notes'::regclass AND attname = 'appointment_id')]) THEN
    RAISE EXCEPTION 'T75: appointment_id nao pode ter UNIQUE';
  END IF;
  -- a coluna nova não é imutável nem cifrada
  IF EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgrelid = 'core.clinical_notes'::regclass AND t.tgname = 'trg_notes_forbid_immutable'
                AND pg_get_triggerdef(t.oid) LIKE '%appointment_id%') THEN
    RAISE EXCEPTION 'T75: appointment_id nao deve ser imutavel';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'notes_appointment_link: OK' AS resultado;
