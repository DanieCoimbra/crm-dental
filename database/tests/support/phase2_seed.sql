-- =====================================================================
-- tests/support/phase2_seed.sql | Fase 2 (F01-F08)
-- Apoio dos testes da Fase 2. Incluir com `\ir support/phase2_seed.sql` DENTRO de uma transação (BEGIN) aberta pelo
-- teste, como administrador do banco de teste. Tudo é revertido no ROLLBACK.
--   * traz o seed da Fase 1 (clínicas A e B, dentista e recepção de cada uma, macros ctx_*, expect_err, expect_rows);
--   * pacientes: pa (ativo, A), pa2 (ativo, A), pa_off (inativo, A), pa_anon (anonimizado, A), pb (ativo, B);
--   * pg_temp.appt_sql(...): monta o INSERT de um agendamento para passar a expect_err / expect_rows.
-- =====================================================================
\ir phase1_seed.sql

\set pa      'f2000000-0000-4000-8000-0000000000a1'
\set pa2     'f2000000-0000-4000-8000-0000000000a2'
\set pa_off  'f2000000-0000-4000-8000-0000000000a3'
\set pa_anon 'f2000000-0000-4000-8000-0000000000a4'
\set pb      'f2000000-0000-4000-8000-0000000000b1'

:ctx_a_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, active, deactivated_at, anonymized_at, created_by) VALUES
  (:'pa',      :'ca', 'Paciente Ativo A',        '1990-01-01', '11999990001', true,  NULL,  NULL,  :'ua_d'),
  (:'pa2',     :'ca', 'Paciente Ativo A Dois',   '1991-01-01', '11999990002', true,  NULL,  NULL,  :'ua_d'),
  (:'pa_off',  :'ca', 'Paciente Inativo A',      '1992-01-01', '11999990003', false, now(), NULL,  :'ua_d'),
  (:'pa_anon', :'ca', 'Paciente anonimizado',    '1993-01-01', '0000000000',  false, now(), now(), :'ua_d');
:ctx_b_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES
  (:'pb', :'cb', 'Paciente Ativo B', '1990-02-02', '11999990004', :'ub_d');

-- INSERT de um agendamento (id, clínica, paciente, autor, início, fim, status). Datas em UTC, sempre no futuro distante.
CREATE FUNCTION pg_temp.appt_sql(p_id text, p_clinic text, p_patient text, p_user text, p_from text, p_to text, p_status text)
  RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by) '
                'VALUES (%L, %L, %L, %L::timestamptz, %L::timestamptz, %L, %L)',
                p_id, p_clinic, p_patient, p_from, p_to, p_status, p_user) $$;

-- UPDATE do status de um agendamento
CREATE FUNCTION pg_temp.set_status_sql(p_id text, p_status text) RETURNS text LANGUAGE sql AS $$
  SELECT format('UPDATE core.appointments SET status = %L WHERE id = %L', p_status, p_id) $$;
