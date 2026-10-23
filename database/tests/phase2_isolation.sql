-- =====================================================================
-- tests/phase2_isolation.sql | Fase 2 / F08-phase2-verification
-- Testes 59, 60 (parte do paciente), 67 e 68 da spec global da Fase 2: isolamento de tenant em appointments,
-- business_hours e clinic_holidays (SELECT, UPDATE, DELETE e INSERT), falha fechada sem sessão ou com papel inválido e
-- autoria verdadeira (created_by = app.user_id) nas três tabelas. holidays é a exceção intencional: dado global.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase2_seed.sql

\set ia 'f2000000-0000-4000-8000-000000000801'
\set ib 'f2000000-0000-4000-8000-000000000802'

-- Fixtures nas DUAS clínicas (como administrador)
:ctx_a_d \gset
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, created_by) VALUES
  (:'ia', :'ca', :'pa', '2031-10-01 08:00+00', '2031-10-01 09:00+00', :'ua_d');
INSERT INTO core.business_hours (clinic_id, weekday, is_open, opens_at, closes_at, created_by) VALUES (:'ca', 1, true, '08:00', '18:00', :'ua_d');
INSERT INTO core.clinic_holidays (clinic_id, holiday_date, name, created_by) VALUES (:'ca', '2031-06-24', 'Feriado da clinica A', :'ua_d');
:ctx_b_d \gset
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, created_by) VALUES
  (:'ib', :'cb', :'pb', '2031-10-01 08:00+00', '2031-10-01 09:00+00', :'ub_d');
INSERT INTO core.business_hours (clinic_id, weekday, is_open, opens_at, closes_at, created_by) VALUES (:'cb', 1, true, '07:00', '15:00', :'ub_d');
INSERT INTO core.clinic_holidays (clinic_id, holiday_date, name, created_by) VALUES (:'cb', '2031-06-24', 'Feriado da clinica B', :'ub_d');

SET SESSION AUTHORIZATION crm_app;

-- ---------------------------------------------------------------------
-- T59: sob A (dentista e recepção), 0 linhas de B em SELECT/UPDATE/DELETE; INSERT com clinic_id = B falha
-- ---------------------------------------------------------------------
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T59 A ve so o seu agendamento', 'SELECT 1 FROM core.appointments', 1);
SELECT pg_temp.expect_rows('T59 A ve so o seu horario', 'SELECT 1 FROM core.business_hours', 1);
SELECT pg_temp.expect_rows('T59 A ve so o seu feriado', 'SELECT 1 FROM core.clinic_holidays', 1);
SELECT pg_temp.expect_rows('T59 A nao le o agendamento de B por id', format('SELECT 1 FROM core.appointments WHERE id = %L', :'ib'), 0);
SELECT pg_temp.expect_rows('T59 A nao altera o agendamento de B', format('UPDATE core.appointments SET notes = %L WHERE id = %L', 'x', :'ib'), 0);
SELECT pg_temp.expect_rows('T59 A nao altera o horario de B', format('UPDATE core.business_hours SET closes_at = %L WHERE clinic_id = %L', '20:00', :'cb'), 0);
SELECT pg_temp.expect_rows('T59 A nao altera o feriado de B', format('UPDATE core.clinic_holidays SET name = %L WHERE clinic_id = %L', 'x', :'cb'), 0);
SELECT pg_temp.expect_rows('T59 A nao remove o feriado de B', format('DELETE FROM core.clinic_holidays WHERE clinic_id = %L', :'cb'), 0);
-- INSERT com clinic_id de B
SELECT pg_temp.expect_err('T59 A nao insere agendamento em B', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'cb', :'pb', :'ua_d', '2031-10-02 08:00+00', '2031-10-02 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T59 A nao insere horario em B', '42501', format(
  'INSERT INTO core.business_hours (clinic_id, weekday, is_open, opens_at, closes_at, created_by) VALUES (%L, 2, true, %L, %L, %L)', :'cb', '08:00', '12:00', :'ua_d'));
SELECT pg_temp.expect_err('T59 A nao insere feriado em B', '42501', format(
  'INSERT INTO core.clinic_holidays (clinic_id, holiday_date, name, created_by) VALUES (%L, %L, %L, %L)', :'cb', '2031-12-31', 'Feriado', :'ua_d'));
-- mudar o clinic_id da própria linha para B: imutável
SELECT pg_temp.expect_err('T59 mover o agendamento para a clinica B', '23514', format('UPDATE core.appointments SET clinic_id = %L WHERE id = %L', :'cb', :'ia'));
-- holidays é global e legível para qualquer clínica
SELECT pg_temp.expect_rows('T59 holidays e legivel', 'SELECT 1 FROM core.holidays', 50);

:ctx_a_r \gset
SELECT pg_temp.expect_rows('T59 recepcao de A ve so o agendamento de A', 'SELECT 1 FROM core.appointments', 1);
SELECT pg_temp.expect_rows('T59 recepcao de A ve so o horario de A', 'SELECT 1 FROM core.business_hours', 1);
SELECT pg_temp.expect_rows('T59 recepcao de A ve so o feriado de A', 'SELECT 1 FROM core.clinic_holidays', 1);
SELECT pg_temp.expect_rows('T59 recepcao de A nao altera o agendamento de B', format('UPDATE core.appointments SET notes = %L WHERE id = %L', 'x', :'ib'), 0);
SELECT pg_temp.expect_err('T59 recepcao de A nao insere agendamento em B', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'cb', :'pb', :'ua_r', '2031-10-02 08:00+00', '2031-10-02 09:00+00', 'SCHEDULED'));

-- simetria: B enxerga só B
:ctx_b_d \gset
SELECT pg_temp.expect_rows('T59 B ve so o seu agendamento', 'SELECT 1 FROM core.appointments', 1);
SELECT pg_temp.expect_rows('T59 B nao altera o agendamento de A', format('UPDATE core.appointments SET notes = %L WHERE id = %L', 'x', :'ia'), 0);
SELECT pg_temp.expect_rows('T59 B nao remove o feriado de A', format('DELETE FROM core.clinic_holidays WHERE holiday_date = %L AND clinic_id = %L', '2031-06-24', :'ca'), 0);
:ctx_b_r \gset
SELECT pg_temp.expect_rows('T59 recepcao de B ve so a agenda de B', 'SELECT 1 FROM core.appointments', 1);

-- ---------------------------------------------------------------------
-- T59/T67: sem sessão, sem clínica, sem papel ou com papel inválido: tudo negado (falha fechada); holidays segue legível
-- ---------------------------------------------------------------------
:ctx_none \gset
SELECT pg_temp.expect_rows('T59 sem sessao: 0 agendamentos', 'SELECT 1 FROM core.appointments', 0);
SELECT pg_temp.expect_rows('T59 sem sessao: 0 horarios', 'SELECT 1 FROM core.business_hours', 0);
SELECT pg_temp.expect_rows('T59 sem sessao: 0 feriados da clinica', 'SELECT 1 FROM core.clinic_holidays', 0);
SELECT pg_temp.expect_err('T59 sem sessao nao insere agendamento', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', '2031-10-03 08:00+00', '2031-10-03 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_rows('T67 sem sessao: holidays segue legivel', 'SELECT 1 FROM core.holidays', 50);

:ctx_a_norole \gset
SELECT pg_temp.expect_rows('T67 sem papel: 0 agendamentos', 'SELECT 1 FROM core.appointments', 0);
SELECT pg_temp.expect_rows('T67 sem papel: 0 horarios', 'SELECT 1 FROM core.business_hours', 0);
SELECT pg_temp.expect_rows('T67 sem papel: 0 feriados da clinica', 'SELECT 1 FROM core.clinic_holidays', 0);
SELECT pg_temp.expect_err('T67 sem papel nao insere agendamento', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', '2031-10-03 08:00+00', '2031-10-03 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_rows('T67 sem papel: nao altera', format('UPDATE core.appointments SET notes = %L WHERE id = %L', 'x', :'ia'), 0);
SELECT pg_temp.expect_rows('T67 sem papel: holidays segue legivel', 'SELECT 1 FROM core.holidays', 50);

-- papel inválido (comparação exata): 'ADMIN', minúsculas e papel de outro sistema
SELECT set_config('app.clinic_id', :'ca', true) || set_config('app.user_id', :'ua_d', true) AS _ \gset
SELECT set_config('app.role', 'ADMIN', true) AS _ \gset
SELECT pg_temp.expect_rows('T67 papel ADMIN: 0 agendamentos', 'SELECT 1 FROM core.appointments', 0);
SELECT pg_temp.expect_rows('T67 papel ADMIN: 0 horarios', 'SELECT 1 FROM core.business_hours', 0);
SELECT pg_temp.expect_rows('T67 papel ADMIN: 0 feriados da clinica', 'SELECT 1 FROM core.clinic_holidays', 0);
SELECT pg_temp.expect_err('T67 papel ADMIN nao insere horario', '42501', format(
  'INSERT INTO core.business_hours (clinic_id, weekday, is_open, opens_at, closes_at, created_by) VALUES (%L, 3, true, %L, %L, %L)', :'ca', '08:00', '12:00', :'ua_d'));
SELECT set_config('app.role', 'dentist_admin', true) AS _ \gset
SELECT pg_temp.expect_rows('T67 papel em minusculas: 0 agendamentos', 'SELECT 1 FROM core.appointments', 0);
SELECT set_config('app.role', 'DENTIST_ADMIN ', true) AS _ \gset
SELECT pg_temp.expect_rows('T67 papel com espaco: 0 agendamentos', 'SELECT 1 FROM core.appointments', 0);

-- ---------------------------------------------------------------------
-- T68: autoria verdadeira (created_by = app.user_id) nas três tabelas
-- ---------------------------------------------------------------------
:ctx_a_d \gset
SELECT pg_temp.expect_err('T68 agendamento em nome de outro usuario', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_r', '2031-10-04 08:00+00', '2031-10-04 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T68 horario em nome de outro usuario', '42501', format(
  'INSERT INTO core.business_hours (clinic_id, weekday, is_open, opens_at, closes_at, created_by) VALUES (%L, 3, true, %L, %L, %L)', :'ca', '08:00', '12:00', :'ua_r'));
SELECT pg_temp.expect_err('T68 feriado em nome de outro usuario', '42501', format(
  'INSERT INTO core.clinic_holidays (clinic_id, holiday_date, name, created_by) VALUES (%L, %L, %L, %L)', :'ca', '2031-12-31', 'Feriado', :'ua_r'));
-- usuário de outra clínica: a política recusa antes da FK composta
SELECT pg_temp.expect_err('T68 agendamento em nome de usuario de B', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ub_d', '2031-10-04 08:00+00', '2031-10-04 09:00+00', 'SCHEDULED'));
-- e a FK composta cobre o caso em que a política deixaria passar (administrador, que não passa pela RLS)
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_err('T60 FK composta: created_by de outra clinica', '23503',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ub_d', '2031-10-04 08:00+00', '2031-10-04 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T60 FK composta: horario com created_by de outra clinica', '23503', format(
  'INSERT INTO core.business_hours (clinic_id, weekday, is_open, opens_at, closes_at, created_by) VALUES (%L, 3, true, %L, %L, %L)', :'ca', '08:00', '12:00', :'ub_d'));
SELECT pg_temp.expect_err('T60 FK composta: feriado com created_by de outra clinica', '23503', format(
  'INSERT INTO core.clinic_holidays (clinic_id, holiday_date, name, created_by) VALUES (%L, %L, %L, %L)', :'ca', '2031-12-31', 'Feriado', :'ub_d'));
SELECT pg_temp.expect_err('T60 FK composta: clinica inexistente', '23503',
  pg_temp.appt_sql(gen_random_uuid()::text, gen_random_uuid()::text, :'pa', :'ua_d', '2031-10-04 08:00+00', '2031-10-04 09:00+00', 'SCHEDULED'));

ROLLBACK;
\o
SELECT 'phase2_isolation: OK' AS resultado;
