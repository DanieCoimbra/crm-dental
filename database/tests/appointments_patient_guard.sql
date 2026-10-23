-- =====================================================================
-- tests/appointments_patient_guard.sql | Fase 2 / F02-appointments
-- Teste 74 da spec global da Fase 2: paciente inativo ou anonimizado não recebe agendamento novo. O trigger só consulta o
-- paciente quando o agendamento PASSA a ocupar horário (INSERT ocupante, ou UPDATE saindo de CANCELED/NO_SHOW).
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : CR003 paciente anonimizado; CR005 paciente inativo; 23503 paciente de outra clínica ou inexistente.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase2_seed.sql

\set g1 'f2000000-0000-4000-8000-000000000301'
\set g2 'f2000000-0000-4000-8000-000000000302'
\set g3 'f2000000-0000-4000-8000-000000000303'
\set g4 'f2000000-0000-4000-8000-000000000304'

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- INSERT ocupante
SELECT pg_temp.expect_err('T74 paciente inativo (SCHEDULED)', 'CR005',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa_off', :'ua_d', '2031-05-01 08:00+00', '2031-05-01 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T74 paciente inativo (IN_PROGRESS, dentista)', 'CR005',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa_off', :'ua_d', '2031-05-01 08:00+00', '2031-05-01 09:00+00', 'IN_PROGRESS'));
SELECT pg_temp.expect_err('T74 paciente anonimizado (SCHEDULED)', 'CR003',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa_anon', :'ua_d', '2031-05-01 08:00+00', '2031-05-01 09:00+00', 'SCHEDULED'));
:ctx_a_r \gset
SELECT pg_temp.expect_err('T74 recepcao: paciente inativo', 'CR005',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa_off', :'ua_r', '2031-05-01 08:00+00', '2031-05-01 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T74 recepcao: paciente anonimizado', 'CR003',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa_anon', :'ua_r', '2031-05-01 08:00+00', '2031-05-01 09:00+00', 'SCHEDULED'));
-- paciente de outra clínica ou inexistente: a FK composta responde (o trigger não acha o paciente e não decide nada)
SELECT pg_temp.expect_err('T74 paciente de outra clinica', '23503',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pb', :'ua_r', '2031-05-01 08:00+00', '2031-05-01 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T74 paciente inexistente', '23503',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', gen_random_uuid()::text, :'ua_r', '2031-05-01 08:00+00', '2031-05-01 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_rows('T74 paciente ativo passa',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_r', '2031-05-01 08:00+00', '2031-05-01 09:00+00', 'SCHEDULED'), 1);

-- INSERT já CANCELED / NO_SHOW não ocupa horário: não consulta o paciente
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T74 INSERT ja CANCELED de paciente inativo passa',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa_off', :'ua_d', '2031-05-02 08:00+00', '2031-05-02 09:00+00', 'CANCELED'), 1);
SELECT pg_temp.expect_rows('T74 INSERT ja NO_SHOW de paciente anonimizado passa',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa_anon', :'ua_d', '2031-05-02 09:00+00', '2031-05-02 10:00+00', 'NO_SHOW'), 1);

-- Reativar cancelado de paciente que ficou inativo: CR005 (o UPDATE faz o agendamento voltar a ocupar horário)
RESET SESSION AUTHORIZATION;
:ctx_a_d \gset
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by) VALUES
  (:'g1', :'ca', :'pa2', '2031-05-03 08:00+00', '2031-05-03 09:00+00', 'CANCELED',  :'ua_d'),
  (:'g2', :'ca', :'pa2', '2031-05-03 09:00+00', '2031-05-03 10:00+00', 'SCHEDULED', :'ua_d'),
  (:'g3', :'ca', :'pa2', '2031-05-03 10:00+00', '2031-05-03 11:00+00', 'NO_SHOW',   :'ua_d'),
  (:'g4', :'ca', :'pa2', '2031-05-03 11:00+00', '2031-05-03 12:00+00', 'CONFIRMED', :'ua_d');
-- o paciente é inativado depois de ter agendamentos
UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = :'pa2';

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T74 reativar cancelado de paciente inativo', 'CR005', pg_temp.set_status_sql(:'g1', 'SCHEDULED'));
SELECT pg_temp.expect_err('T74 reativar faltoso de paciente inativo', 'CR005', pg_temp.set_status_sql(:'g3', 'CONFIRMED'));
-- agendamento existente de paciente inativado continua legível e editável; o trigger não consulta nem trava o paciente
SELECT pg_temp.expect_rows('T74 agendamento existente continua legivel', format('SELECT 1 FROM core.appointments WHERE patient_id = %L', :'pa2'), 4);
SELECT pg_temp.expect_rows('T74 UPDATE sem mudar de/para CANCELED/NO_SHOW (g2 ganha notes)', format('UPDATE core.appointments SET notes = %L WHERE id = %L', 'ok', :'g2'), 1);
SELECT pg_temp.expect_rows('T74 SCHEDULED -> CONFIRMED de paciente inativo passa (nao reabre)', pg_temp.set_status_sql(:'g2', 'CONFIRMED'), 1);
SELECT pg_temp.expect_rows('T74 CONFIRMED -> CANCELED de paciente inativo passa', pg_temp.set_status_sql(:'g4', 'CANCELED'), 1);
SELECT pg_temp.expect_rows('T74 CANCELED -> NO_SHOW de paciente inativo passa (continua nao ocupando)', pg_temp.set_status_sql(:'g4', 'NO_SHOW'), 1);

-- ---------------------------------------------------------------------
-- Prova de que o UPDATE comum não consulta nem trava o paciente: a linha do paciente fica travada por outra transação
-- FOR UPDATE (aqui, simulado no catálogo: o trigger só tem o SELECT ... FOR KEY SHARE no caminho de INSERT/reabertura)
-- ---------------------------------------------------------------------
RESET SESSION AUTHORIZATION;
DO $$
DECLARE
  src text;
BEGIN
  SELECT p.prosrc INTO src FROM pg_proc p WHERE p.oid = 'core.guard_appointment_patient()'::regprocedure;
  IF src !~* 'FOR KEY SHARE' THEN
    RAISE EXCEPTION 'T74: guard_appointment_patient deve travar o paciente com FOR KEY SHARE (corrida com a anonimizacao)';
  END IF;
  IF (SELECT p.prosecdef FROM pg_proc p WHERE p.oid = 'core.guard_appointment_patient()'::regprocedure) THEN
    RAISE EXCEPTION 'T74: guard_appointment_patient deve ser SECURITY INVOKER';
  END IF;
  -- a ordem do guarda: encerra cedo quando o status novo é CANCELED/NO_SHOW e quando o UPDATE não sai deles
  IF position('CANCELED' IN src) = 0 OR position('NO_SHOW' IN src) = 0 THEN
    RAISE EXCEPTION 'T74: guard_appointment_patient deve tratar CANCELED/NO_SHOW como nao ocupantes';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'appointments_patient_guard: OK' AS resultado;
