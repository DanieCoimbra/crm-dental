-- =====================================================================
-- tests/business_hours.sql | Fase 2 / F03-business-hours
-- Testes 70 e 69 (parte) da spec global da Fase 2: invariantes do horário comercial (uma linha por clínica e dia), quem
-- lê e quem grava (RECEPTIONIST só lê; DENTIST_ADMIN grava; ninguém apaga), autoria e imutáveis.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 23514 CHECK ou coluna imutável; 23505 dia já configurado; 42501 papel, autoria ou DELETE.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase2_seed.sql

-- INSERT de uma linha de horário (clínica, usuário, dia, aberto, abre, fecha, almoço início, almoço fim)
CREATE FUNCTION pg_temp.bh_sql(p_clinic text, p_user text, p_day int, p_open boolean, p_opens text, p_closes text, p_l1 text, p_l2 text)
  RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.business_hours (clinic_id, weekday, is_open, opens_at, closes_at, lunch_starts_at, lunch_ends_at, created_by) '
                'VALUES (%L, %s, %L, %L, %L, %L, %L, %L)', p_clinic, p_day, p_open, p_opens, p_closes, p_l1, p_l2, p_user) $$;

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- ---------------------------------------------------------------------
-- T70: invariantes
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_rows('T70 segunda aberta com almoco', pg_temp.bh_sql(:'ca', :'ua_d', 1, true, '08:00', '18:00', '12:00', '13:30'), 1);
SELECT pg_temp.expect_rows('T70 terca aberta sem almoco',   pg_temp.bh_sql(:'ca', :'ua_d', 2, true, '09:00', '17:00', NULL, NULL), 1);
SELECT pg_temp.expect_rows('T70 domingo fechado sem horarios', pg_temp.bh_sql(:'ca', :'ua_d', 7, false, NULL, NULL, NULL, NULL), 1);
SELECT pg_temp.expect_rows('T70 fechamento as 24:00 e aceito', pg_temp.bh_sql(:'ca', :'ua_d', 6, true, '08:00', '24:00', NULL, NULL), 1);

SELECT pg_temp.expect_err('T70 fechado com horario preenchido', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, false, '08:00', '18:00', NULL, NULL));
SELECT pg_temp.expect_err('T70 fechado com almoco preenchido', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, false, NULL, NULL, '12:00', '13:00'));
SELECT pg_temp.expect_err('T70 aberto sem horario', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, NULL, NULL, NULL, NULL));
SELECT pg_temp.expect_err('T70 aberto sem fechamento', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, '08:00', NULL, NULL, NULL));
SELECT pg_temp.expect_err('T70 abre depois de fechar', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, '18:00', '08:00', NULL, NULL));
SELECT pg_temp.expect_err('T70 abre e fecha na mesma hora', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, '08:00', '08:00', NULL, NULL));
SELECT pg_temp.expect_err('T70 almoco antes de abrir', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, '08:00', '18:00', '07:00', '08:30'));
SELECT pg_temp.expect_err('T70 almoco depois de fechar', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, '08:00', '18:00', '17:00', '18:30'));
SELECT pg_temp.expect_err('T70 almoco comecando na abertura', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, '08:00', '18:00', '08:00', '09:00'));
SELECT pg_temp.expect_err('T70 almoco terminando no fechamento', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, '08:00', '18:00', '17:00', '18:00'));
SELECT pg_temp.expect_err('T70 almoco com inicio depois do fim', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, '08:00', '18:00', '13:00', '12:00'));
SELECT pg_temp.expect_err('T70 so a ponta inicial do almoco', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, '08:00', '18:00', '12:00', NULL));
SELECT pg_temp.expect_err('T70 so a ponta final do almoco', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, '08:00', '18:00', NULL, '13:00'));
SELECT pg_temp.expect_err('T70 weekday 0', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 0, true, '08:00', '18:00', NULL, NULL));
SELECT pg_temp.expect_err('T70 weekday 8', '23514', pg_temp.bh_sql(:'ca', :'ua_d', 8, true, '08:00', '18:00', NULL, NULL));
-- uma só linha por (clínica, dia)
SELECT pg_temp.expect_err('T70 segunda linha do mesmo dia', '23505', pg_temp.bh_sql(:'ca', :'ua_d', 1, true, '09:00', '12:00', NULL, NULL));
-- o mesmo dia em outra clínica passa
:ctx_b_d \gset
SELECT pg_temp.expect_rows('T70 o mesmo dia na clinica B passa', pg_temp.bh_sql(:'cb', :'ub_d', 1, true, '07:00', '15:00', NULL, NULL), 1);
:ctx_a_d \gset

-- UPDATE: abrir o domingo e trocar o almoço na mesma instrução (atômico, o motivo de a linha ser única por dia)
SELECT pg_temp.expect_rows('T70 UPDATE: domingo passa a abrir', format(
  'UPDATE core.business_hours SET is_open = true, opens_at = %L, closes_at = %L WHERE clinic_id = %L AND weekday = 7', '09:00', '13:00', :'ca'), 1);
SELECT pg_temp.expect_rows('T70 UPDATE: troca do almoco numa instrucao', format(
  'UPDATE core.business_hours SET lunch_starts_at = %L, lunch_ends_at = %L WHERE clinic_id = %L AND weekday = 1', '11:30', '13:00', :'ca'), 1);
SELECT pg_temp.expect_err('T70 UPDATE: fechar sem limpar os horarios', '23514', format(
  'UPDATE core.business_hours SET is_open = false WHERE clinic_id = %L AND weekday = 2', :'ca'));
SELECT pg_temp.expect_rows('T70 UPDATE: fechar limpando tudo', format(
  'UPDATE core.business_hours SET is_open = false, opens_at = NULL, closes_at = NULL, lunch_starts_at = NULL, lunch_ends_at = NULL WHERE clinic_id = %L AND weekday = 2', :'ca'), 1);
-- upsert pelo backend (ON CONFLICT (clinic_id, weekday) DO UPDATE): o dentista tem SELECT, INSERT e UPDATE
SELECT pg_temp.expect_rows('T70 upsert por (clinic_id, weekday)', format(
  'INSERT INTO core.business_hours (clinic_id, weekday, is_open, opens_at, closes_at, created_by) VALUES (%L, 2, true, %L, %L, %L) '
  'ON CONFLICT (clinic_id, weekday) DO UPDATE SET is_open = EXCLUDED.is_open, opens_at = EXCLUDED.opens_at, closes_at = EXCLUDED.closes_at',
  :'ca', '10:00', '16:00', :'ua_d'), 1);
-- imutáveis
SELECT pg_temp.expect_err('T70 weekday e imutavel', '23514', format('UPDATE core.business_hours SET weekday = 5 WHERE clinic_id = %L AND weekday = 1', :'ca'));
SELECT pg_temp.expect_err('T70 created_by e imutavel', '23514', format('UPDATE core.business_hours SET created_by = %L WHERE clinic_id = %L AND weekday = 1', :'ua_r', :'ca'));

-- ---------------------------------------------------------------------
-- T69 (parte): quem lê e quem grava
-- ---------------------------------------------------------------------
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T69 recepcao le a semana da clinica A', 'SELECT 1 FROM core.business_hours', 4);
SELECT pg_temp.expect_err('T69 recepcao nao insere horario', '42501', pg_temp.bh_sql(:'ca', :'ua_r', 3, true, '08:00', '18:00', NULL, NULL));
SELECT pg_temp.expect_rows('T69 recepcao nao altera horario (0 linhas)', format('UPDATE core.business_hours SET closes_at = %L WHERE clinic_id = %L AND weekday = 1', '19:00', :'ca'), 0);
SELECT pg_temp.expect_err('T69 recepcao nao apaga horario', '42501', 'DELETE FROM core.business_hours');
:ctx_a_d \gset
SELECT pg_temp.expect_err('T69 nem o dentista apaga horario (dia fechado e is_open = false)', '42501', 'DELETE FROM core.business_hours');
SELECT pg_temp.expect_err('T68 dentista insere em nome da recepcao', '42501', pg_temp.bh_sql(:'ca', :'ua_r', 3, true, '08:00', '18:00', NULL, NULL));
:ctx_a_d_nouser \gset
SELECT pg_temp.expect_err('T68 sem app.user_id nao insere', '42501', pg_temp.bh_sql(:'ca', :'ua_d', 3, true, '08:00', '18:00', NULL, NULL));

RESET SESSION AUTHORIZATION;
-- a linha do dia continua inalterada pela recepção (11:30/13:00 do UPDATE do dentista; fecha às 18:00)
DO $$
BEGIN
  IF (SELECT closes_at FROM core.business_hours WHERE clinic_id = 'f1000000-0000-4000-8000-00000000000a' AND weekday = 1) <> TIME '18:00' THEN
    RAISE EXCEPTION 'T69: a recepcao alterou o horario';
  END IF;
  -- hora local: as colunas são time(0) sem fuso
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'core' AND table_name = 'business_hours'
              AND column_name IN ('opens_at', 'closes_at', 'lunch_starts_at', 'lunch_ends_at')
              AND (data_type <> 'time without time zone' OR datetime_precision <> 0)) THEN
    RAISE EXCEPTION 'T70: horarios devem ser time(0) sem fuso (hora local da clinica)';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'business_hours: OK' AS resultado;
