-- =====================================================================
-- tests/appointments_roles.sql | Fase 2 / F02-appointments
-- Testes 65, 66 e 68 (parte) da spec global da Fase 2: o que cada papel grava em appointments. A RECEPTIONIST só cria
-- SCHEDULED e só altera linhas SCHEDULED/CONFIRMED, gravando SCHEDULED/CONFIRMED/CANCELED/NO_SHOW; o DENTIST_ADMIN grava
-- qualquer status sobre qualquer linha (a ORDEM das transições é do backend, decisão A4). Também: DELETE negado e autoria.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 42501 política (WITH CHECK, autoria) ou falta de privilégio (DELETE); linha invisível para alterar = 0 linhas
--            afetadas, sem erro.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase2_seed.sql

\set s1 'f2000000-0000-4000-8000-000000000201'
\set s2 'f2000000-0000-4000-8000-000000000202'
\set s3 'f2000000-0000-4000-8000-000000000203'
\set s4 'f2000000-0000-4000-8000-000000000204'
\set s5 'f2000000-0000-4000-8000-000000000205'
\set s6 'f2000000-0000-4000-8000-000000000206'
\set s7 'f2000000-0000-4000-8000-000000000207'

-- Fixtures como administrador: uma linha por status (uma hora cada, dia 2031-04-01), na clínica A
:ctx_a_d \gset
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by) VALUES
  (:'s1', :'ca', :'pa', '2031-04-01 08:00+00', '2031-04-01 09:00+00', 'SCHEDULED',   :'ua_d'),
  (:'s2', :'ca', :'pa', '2031-04-01 09:00+00', '2031-04-01 10:00+00', 'CONFIRMED',   :'ua_d'),
  (:'s3', :'ca', :'pa', '2031-04-01 10:00+00', '2031-04-01 11:00+00', 'IN_PROGRESS', :'ua_d'),
  (:'s4', :'ca', :'pa', '2031-04-01 11:00+00', '2031-04-01 12:00+00', 'COMPLETED',   :'ua_d'),
  (:'s5', :'ca', :'pa', '2031-04-01 12:00+00', '2031-04-01 13:00+00', 'CANCELED',    :'ua_d'),
  (:'s6', :'ca', :'pa', '2031-04-01 13:00+00', '2031-04-01 14:00+00', 'NO_SHOW',     :'ua_d'),
  (:'s7', :'ca', :'pa', '2031-04-01 14:00+00', '2031-04-01 15:00+00', 'SCHEDULED',   :'ua_d');

SET SESSION AUTHORIZATION crm_app;

-- ---------------------------------------------------------------------
-- T65: RECEPTIONIST
-- ---------------------------------------------------------------------
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T65 recepcao le todos os status da clinica', 'SELECT 1 FROM core.appointments', 7);

-- INSERT: só SCHEDULED
SELECT pg_temp.expect_rows('T65 recepcao insere SCHEDULED',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_r', '2031-04-02 08:00+00', '2031-04-02 09:00+00', 'SCHEDULED'), 1);
SELECT pg_temp.expect_err('T65 recepcao nao insere CONFIRMED', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_r', '2031-04-02 09:00+00', '2031-04-02 10:00+00', 'CONFIRMED'));
SELECT pg_temp.expect_err('T65 recepcao nao insere IN_PROGRESS', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_r', '2031-04-02 09:00+00', '2031-04-02 10:00+00', 'IN_PROGRESS'));
SELECT pg_temp.expect_err('T65 recepcao nao insere COMPLETED', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_r', '2031-04-02 09:00+00', '2031-04-02 10:00+00', 'COMPLETED'));
SELECT pg_temp.expect_err('T65 recepcao nao insere CANCELED', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_r', '2031-04-02 09:00+00', '2031-04-02 10:00+00', 'CANCELED'));
-- T68: a autoria tem de ser o app.user_id
SELECT pg_temp.expect_err('T68 recepcao insere em nome do dentista', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', '2031-04-02 10:00+00', '2031-04-02 11:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T68 recepcao insere em nome de usuario de outra clinica', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ub_d', '2031-04-02 10:00+00', '2031-04-02 11:00+00', 'SCHEDULED'));

-- UPDATE: transições que a recepção pode gravar, sobre linhas SCHEDULED/CONFIRMED
SELECT pg_temp.expect_rows('T65 SCHEDULED -> CONFIRMED', pg_temp.set_status_sql(:'s1', 'CONFIRMED'), 1);
SELECT pg_temp.expect_rows('T65 CONFIRMED -> CANCELED',  pg_temp.set_status_sql(:'s1', 'CANCELED'), 1);
SELECT pg_temp.expect_rows('T65 CONFIRMED -> NO_SHOW',   pg_temp.set_status_sql(:'s2', 'NO_SHOW'), 1);
SELECT pg_temp.expect_rows('T65 SCHEDULED -> NO_SHOW',   pg_temp.set_status_sql(:'s7', 'NO_SHOW'), 1);
-- s7 voltou a ser lida como NO_SHOW: para os próximos testes, o administrador a restaura
RESET SESSION AUTHORIZATION;
UPDATE core.appointments SET status = 'SCHEDULED' WHERE id = :'s7';
SET SESSION AUTHORIZATION crm_app;
:ctx_a_r \gset

-- remarcar e editar notes de linha SCHEDULED
SELECT pg_temp.expect_rows('T65 recepcao remarca s7', format(
  'UPDATE core.appointments SET starts_at = %L::timestamptz, ends_at = %L::timestamptz WHERE id = %L',
  '2031-04-01 16:00+00', '2031-04-01 17:00+00', :'s7'), 1);
SELECT pg_temp.expect_rows('T65 recepcao edita notes de s7', format('UPDATE core.appointments SET notes = %L WHERE id = %L', 'retorno', :'s7'), 1);

-- status que a recepção NÃO grava: WITH CHECK
SELECT pg_temp.expect_err('T65 SCHEDULED -> IN_PROGRESS falha', '42501', pg_temp.set_status_sql(:'s7', 'IN_PROGRESS'));
SELECT pg_temp.expect_err('T65 SCHEDULED -> COMPLETED falha',   '42501', pg_temp.set_status_sql(:'s7', 'COMPLETED'));

-- linhas que a recepção não enxerga para alterar: 0 linhas afetadas, sem erro
SELECT pg_temp.expect_rows('T65 IN_PROGRESS intocavel (status)', pg_temp.set_status_sql(:'s3', 'CANCELED'), 0);
SELECT pg_temp.expect_rows('T65 IN_PROGRESS intocavel (notes)', format('UPDATE core.appointments SET notes = %L WHERE id = %L', 'x', :'s3'), 0);
SELECT pg_temp.expect_rows('T65 COMPLETED intocavel',            pg_temp.set_status_sql(:'s4', 'CANCELED'), 0);
SELECT pg_temp.expect_rows('T65 CANCELED nao e reaberto pela recepcao', pg_temp.set_status_sql(:'s5', 'SCHEDULED'), 0);
SELECT pg_temp.expect_rows('T65 NO_SHOW nao e reaberto pela recepcao',  pg_temp.set_status_sql(:'s6', 'SCHEDULED'), 0);
SELECT pg_temp.expect_rows('T65 s1 cancelado pela recepcao nao volta pela recepcao', pg_temp.set_status_sql(:'s1', 'SCHEDULED'), 0);
-- DELETE: sem privilégio
SELECT pg_temp.expect_err('T65 recepcao nao apaga', '42501', format('DELETE FROM core.appointments WHERE id = %L', :'s7'));

-- ---------------------------------------------------------------------
-- T66: DENTIST_ADMIN grava qualquer status sobre qualquer linha (o banco não valida a ordem: decisão A4)
-- ---------------------------------------------------------------------
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T66 COMPLETED -> SCHEDULED (fora de ordem, passa)', pg_temp.set_status_sql(:'s4', 'SCHEDULED'), 1);
SELECT pg_temp.expect_rows('T66 IN_PROGRESS -> COMPLETED',                      pg_temp.set_status_sql(:'s3', 'COMPLETED'), 1);
SELECT pg_temp.expect_rows('T66 CANCELED -> SCHEDULED (reativar)',              pg_temp.set_status_sql(:'s1', 'SCHEDULED'), 1);
SELECT pg_temp.expect_rows('T66 CANCELED -> IN_PROGRESS (pula etapas)',         pg_temp.set_status_sql(:'s5', 'IN_PROGRESS'), 1);
SELECT pg_temp.expect_rows('T66 NO_SHOW -> CONFIRMED',                          pg_temp.set_status_sql(:'s6', 'CONFIRMED'), 1);
SELECT pg_temp.expect_rows('T66 dentista insere direto em IN_PROGRESS',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', '2031-04-03 08:00+00', '2031-04-03 09:00+00', 'IN_PROGRESS'), 1);
SELECT pg_temp.expect_rows('T66 dentista insere direto em COMPLETED',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', '2031-04-03 09:00+00', '2031-04-03 10:00+00', 'COMPLETED'), 1);
SELECT pg_temp.expect_err('T66 dentista nao apaga', '42501', format('DELETE FROM core.appointments WHERE id = %L', :'s1'));
-- autoria vale para o dentista também
SELECT pg_temp.expect_err('T68 dentista insere em nome da recepcao', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_r', '2031-04-03 10:00+00', '2031-04-03 11:00+00', 'SCHEDULED'));
:ctx_a_d_user_b \gset
SELECT pg_temp.expect_err('T68 dentista com app.user_id de outra clinica', '23503',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ub_d', '2031-04-03 10:00+00', '2031-04-03 11:00+00', 'SCHEDULED'));
:ctx_a_d_nouser \gset
SELECT pg_temp.expect_err('T68 sem app.user_id nao insere', '42501',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', '2031-04-03 10:00+00', '2031-04-03 11:00+00', 'SCHEDULED'));

-- colunas imutáveis (trigger): nem o dentista muda clínica, paciente, autor ou data de criação
:ctx_a_d \gset
SELECT pg_temp.expect_err('T66 patient_id e imutavel', '23514', format('UPDATE core.appointments SET patient_id = %L WHERE id = %L', :'pa2', :'s1'));
SELECT pg_temp.expect_err('T66 created_by e imutavel', '23514', format('UPDATE core.appointments SET created_by = %L WHERE id = %L', :'ua_r', :'s1'));
SELECT pg_temp.expect_err('T66 created_at e imutavel', '23514', format('UPDATE core.appointments SET created_at = now() - interval ''1 day'' WHERE id = %L', :'s1'));
SELECT pg_temp.expect_err('T66 id e imutavel', '23514', format('UPDATE core.appointments SET id = gen_random_uuid() WHERE id = %L', :'s1'));
-- o trigger de imutáveis (BEFORE) vem antes do WITH CHECK da política: mudar a clínica é 23514, não 42501
SELECT pg_temp.expect_err('T66 clinic_id e imutavel', '23514', format('UPDATE core.appointments SET clinic_id = %L WHERE id = %L', :'cb', :'s1'));

RESET SESSION AUTHORIZATION;
ROLLBACK;
\o
SELECT 'appointments_roles: OK' AS resultado;
