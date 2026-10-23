-- =====================================================================
-- tests/appointments_conflict.sql | Fase 2 / F02-appointments
-- Testes 61, 62 e 64 da spec global da Fase 2: conflito de horário (EXCLUDE por clínica), quem ocupa e quem libera o
-- horário, e os CHECKs de intervalo, duração, início, status e notes. A concorrência real (teste 63) está em
-- appointments_concurrency.sh.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 23P01 conflito de horário; 23514 CHECK.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase2_seed.sql

\set a1 'f2000000-0000-4000-8000-000000000101'
\set a2 'f2000000-0000-4000-8000-000000000102'
\set a3 'f2000000-0000-4000-8000-000000000103'

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- ---------------------------------------------------------------------
-- T61: sobreposição na mesma clínica
-- ---------------------------------------------------------------------
-- base: 10:00 às 11:00 (UTC) no dia 2031-03-03
SELECT pg_temp.expect_rows('T61 base 10:00-11:00', pg_temp.appt_sql(:'a1', :'ca', :'pa', :'ua_d', '2031-03-03 10:00+00', '2031-03-03 11:00+00', 'SCHEDULED'), 1);
SELECT pg_temp.expect_err('T61 parcial no fim (10:30-11:30)', '23P01',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-03 10:30+00', '2031-03-03 11:30+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T61 parcial no inicio (09:30-10:30)', '23P01',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-03 09:30+00', '2031-03-03 10:30+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T61 total (10:00-11:00)', '23P01',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-03 10:00+00', '2031-03-03 11:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T61 contido (10:15-10:45)', '23P01',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-03 10:15+00', '2031-03-03 10:45+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T61 que contem (09:00-12:00)', '23P01',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-03 09:00+00', '2031-03-03 12:00+00', 'SCHEDULED'));
-- mesmo paciente também não pode estar em dois horários sobrepostos (a agenda é única por clínica)
SELECT pg_temp.expect_err('T61 mesmo paciente sobreposto', '23P01',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', '2031-03-03 10:30+00', '2031-03-03 10:45+00', 'SCHEDULED'));
-- intervalo semiaberto [início, fim): consecutivos não conflitam
SELECT pg_temp.expect_rows('T61 consecutivo depois (11:00-12:00)',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-03 11:00+00', '2031-03-03 12:00+00', 'SCHEDULED'), 1);
SELECT pg_temp.expect_rows('T61 consecutivo antes (09:00-10:00)',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-03 09:00+00', '2031-03-03 10:00+00', 'SCHEDULED'), 1);
-- clínicas diferentes nunca conflitam (a chave do EXCLUDE tem clinic_id)
:ctx_b_d \gset
SELECT pg_temp.expect_rows('T61 mesmo horario na clinica B passa',
  pg_temp.appt_sql(gen_random_uuid()::text, :'cb', :'pb', :'ub_d', '2031-03-03 10:00+00', '2031-03-03 11:00+00', 'SCHEDULED'), 1);
:ctx_a_d \gset

-- remarcar por UPDATE para cima de outro agendamento também é conflito (23P01 em UPDATE)
SELECT pg_temp.expect_err('T61 remarcar sobre horario ocupado', '23P01',
  format('UPDATE core.appointments SET starts_at = %L::timestamptz, ends_at = %L::timestamptz WHERE id = %L',
         '2031-03-03 10:30+00', '2031-03-03 11:30+00', :'a1'));
-- remarcar para um horário livre passa
SELECT pg_temp.expect_rows('T61 remarcar para horario livre',
  format('UPDATE core.appointments SET starts_at = %L::timestamptz, ends_at = %L::timestamptz WHERE id = %L',
         '2031-03-03 14:00+00', '2031-03-03 15:00+00', :'a1'), 1);
SELECT pg_temp.expect_rows('T61 voltar ao horario original (liberado pela remarcacao)',
  format('UPDATE core.appointments SET starts_at = %L::timestamptz, ends_at = %L::timestamptz WHERE id = %L',
         '2031-03-03 10:00+00', '2031-03-03 11:00+00', :'a1'), 1);

-- ---------------------------------------------------------------------
-- T62: quem ocupa e quem libera o horário
-- ---------------------------------------------------------------------
-- cancelar libera
SELECT pg_temp.expect_rows('T62 cancelar a1', pg_temp.set_status_sql(:'a1', 'CANCELED'), 1);
SELECT pg_temp.expect_rows('T62 horario liberado pelo cancelamento: a2 entra em 10:00-11:00',
  pg_temp.appt_sql(:'a2', :'ca', :'pa2', :'ua_d', '2031-03-03 10:00+00', '2031-03-03 11:00+00', 'SCHEDULED'), 1);
-- reativar o cancelado sobre horário já ocupado: 23P01 (também em UPDATE)
SELECT pg_temp.expect_err('T62 reativar cancelado sobre horario ocupado', '23P01', pg_temp.set_status_sql(:'a1', 'SCHEDULED'));
-- faltoso também libera
SELECT pg_temp.expect_rows('T62 a2 faltou', pg_temp.set_status_sql(:'a2', 'NO_SHOW'), 1);
SELECT pg_temp.expect_rows('T62 NO_SHOW libera: a3 entra em 10:00-11:00',
  pg_temp.appt_sql(:'a3', :'ca', :'pa', :'ua_d', '2031-03-03 10:00+00', '2031-03-03 11:00+00', 'SCHEDULED'), 1);
-- INSERT já CANCELED / NO_SHOW sobre horário ocupado passa (não ocupa)
SELECT pg_temp.expect_rows('T62 INSERT ja CANCELED sobre horario ocupado passa',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-03 10:00+00', '2031-03-03 11:00+00', 'CANCELED'), 1);
SELECT pg_temp.expect_rows('T62 INSERT ja NO_SHOW sobre horario ocupado passa',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-03 10:00+00', '2031-03-03 11:00+00', 'NO_SHOW'), 1);
-- IN_PROGRESS e COMPLETED continuam ocupando
SELECT pg_temp.expect_rows('T62 a3 em atendimento', pg_temp.set_status_sql(:'a3', 'IN_PROGRESS'), 1);
SELECT pg_temp.expect_err('T62 IN_PROGRESS ocupa', '23P01',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-03 10:30+00', '2031-03-03 10:45+00', 'SCHEDULED'));
SELECT pg_temp.expect_rows('T62 a3 finalizado', pg_temp.set_status_sql(:'a3', 'COMPLETED'), 1);
SELECT pg_temp.expect_err('T62 COMPLETED ocupa', '23P01',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-03 10:30+00', '2031-03-03 10:45+00', 'SCHEDULED'));
-- CONFIRMED ocupa
SELECT pg_temp.expect_rows('T62 CONFIRMED sobre horario livre',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa2', :'ua_d', '2031-03-04 10:00+00', '2031-03-04 11:00+00', 'CONFIRMED'), 1);
SELECT pg_temp.expect_err('T62 CONFIRMED ocupa', '23P01',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', '2031-03-04 10:30+00', '2031-03-04 10:45+00', 'SCHEDULED'));

-- ---------------------------------------------------------------------
-- T64: CHECKs (todos 23514); 5 min e 12 h passam
-- ---------------------------------------------------------------------
\set s '2031-03-10'
SELECT pg_temp.expect_err('T64 fim igual ao inicio', '23514',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', :'s' || ' 10:00+00', :'s' || ' 10:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T64 fim antes do inicio', '23514',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', :'s' || ' 10:00+00', :'s' || ' 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T64 duracao de 4 min', '23514',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', :'s' || ' 10:00+00', :'s' || ' 10:04+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T64 duracao de 7 min (nao multipla de 5)', '23514',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', :'s' || ' 10:00+00', :'s' || ' 10:07+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T64 duracao de 13 h', '23514',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', :'s' || ' 06:00+00', :'s' || ' 19:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T64 inicio com segundos', '23514',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', :'s' || ' 10:00:30+00', :'s' || ' 10:30:30+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T64 inicio com fracao de segundo', '23514',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', :'s' || ' 10:00:00.5+00', :'s' || ' 10:30:00.5+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T64 fim com segundos (duracao nao multipla de 5 min)', '23514',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', :'s' || ' 10:00+00', :'s' || ' 10:30:30+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T64 status fora da lista', '23514',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', :'s' || ' 10:00+00', :'s' || ' 10:30+00', 'AGENDADO'));
SELECT pg_temp.expect_err('T64 status em minusculas', '23514',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', :'s' || ' 10:00+00', :'s' || ' 10:30+00', 'scheduled'));
SELECT pg_temp.expect_err('T64 notes so com espacos', '23514', format(
  'INSERT INTO core.appointments (clinic_id, patient_id, starts_at, ends_at, notes, created_by) VALUES (%L, %L, %L::timestamptz, %L::timestamptz, %L, %L)',
  :'ca', :'pa', :'s' || ' 10:00+00', :'s' || ' 10:30+00', '    ', :'ua_d'));
SELECT pg_temp.expect_err('T64 notes com mais de 500 caracteres', '22001', format(
  'INSERT INTO core.appointments (clinic_id, patient_id, starts_at, ends_at, notes, created_by) VALUES (%L, %L, %L::timestamptz, %L::timestamptz, %L, %L)',
  :'ca', :'pa', :'s' || ' 10:00+00', :'s' || ' 10:30+00', repeat('x', 501), :'ua_d'));
SELECT pg_temp.expect_rows('T64 notes com 500 caracteres passa', format(
  'INSERT INTO core.appointments (clinic_id, patient_id, starts_at, ends_at, notes, created_by) VALUES (%L, %L, %L::timestamptz, %L::timestamptz, %L, %L)',
  :'ca', :'pa', :'s' || ' 10:00+00', :'s' || ' 10:30+00', repeat('x', 500), :'ua_d'), 1);
SELECT pg_temp.expect_rows('T64 duracao minima de 5 min passa',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', :'s' || ' 12:00+00', :'s' || ' 12:05+00', 'SCHEDULED'), 1);
SELECT pg_temp.expect_rows('T64 duracao maxima de 12 h passa',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', '2031-03-11 00:00+00', '2031-03-11 12:00+00', 'SCHEDULED'), 1);
SELECT pg_temp.expect_err('T64 duracao de 12 h e 5 min', '23514',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', '2031-03-12 00:00+00', '2031-03-12 12:05+00', 'SCHEDULED'));

-- colunas obrigatórias e FK composta do paciente
SELECT pg_temp.expect_err('T60 paciente de outra clinica', '23503',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pb', :'ua_d', '2031-03-13 10:00+00', '2031-03-13 10:30+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T60 paciente inexistente', '23503',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', gen_random_uuid()::text, :'ua_d', '2031-03-13 10:00+00', '2031-03-13 10:30+00', 'SCHEDULED'));

RESET SESSION AUTHORIZATION;

-- o EXCLUDE existe, é parcial e usa o intervalo semiaberto padrão
DO $$
DECLARE
  d text;
BEGIN
  SELECT pg_get_constraintdef(c.oid) INTO d FROM pg_constraint c WHERE c.conname = 'ex_appt_no_overlap' AND c.contype = 'x';
  IF d IS NULL THEN RAISE EXCEPTION 'T61: ex_appt_no_overlap ausente'; END IF;
  IF d !~ 'USING gist' OR d !~ 'clinic_id WITH =' OR d !~ 'tstzrange\(starts_at, ends_at\) WITH &&'
     OR d !~ 'CANCELED' OR d !~ 'NO_SHOW' THEN
    RAISE EXCEPTION 'T61: ex_appt_no_overlap divergente: %', d;
  END IF;
  IF (SELECT c.condeferrable FROM pg_constraint c WHERE c.conname = 'ex_appt_no_overlap') THEN
    RAISE EXCEPTION 'T61: o EXCLUDE nao pode ser deferrable (a permuta de horarios nao e suportada)';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'appointments_conflict: OK' AS resultado;
