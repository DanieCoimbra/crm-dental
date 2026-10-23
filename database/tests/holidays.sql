-- =====================================================================
-- tests/holidays.sql | Fase 2 / F04-holidays
-- Testes 69 (parte), 71 e 72 da spec global da Fase 2: feriados da clínica (clinic_holidays), feriados nacionais
-- (holidays, somente leitura para a aplicação) e o seed 2026-2030.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 23505 data repetida; 23514 data anterior a 2000 ou nome curto; 42501 papel, autoria ou escrita em holidays.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase2_seed.sql

CREATE FUNCTION pg_temp.chol_sql(p_clinic text, p_user text, p_date text, p_name text) RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.clinic_holidays (clinic_id, holiday_date, name, created_by) VALUES (%L, %L::date, %L, %L)',
                p_clinic, p_date, p_name, p_user) $$;

-- ---------------------------------------------------------------------
-- T72: seed (feito pela V018, já aplicada)
-- ---------------------------------------------------------------------
DO $$
DECLARE
  d date;
BEGIN
  IF (SELECT count(*) FROM core.holidays) <> 50 THEN
    RAISE EXCEPTION 'T72: o seed deve ter 50 feriados (obtido %)', (SELECT count(*) FROM core.holidays);
  END IF;
  FOREACH d IN ARRAY ARRAY[DATE '2026-04-03', DATE '2027-03-26', DATE '2028-04-14', DATE '2029-03-30', DATE '2030-04-19'] LOOP
    IF NOT EXISTS (SELECT 1 FROM core.holidays WHERE holiday_date = d AND name = 'Sexta-feira Santa') THEN
      RAISE EXCEPTION 'T72: Sexta-feira Santa ausente em %', d;
    END IF;
  END LOOP;
  -- 10 por ano, 2026 a 2030; nada fora desse intervalo
  IF EXISTS (SELECT 1 FROM core.holidays GROUP BY extract(year FROM holiday_date) HAVING count(*) <> 10) THEN
    RAISE EXCEPTION 'T72: cada ano deve ter exatamente 10 feriados';
  END IF;
  IF (SELECT min(extract(year FROM holiday_date)) FROM core.holidays) <> 2026
     OR (SELECT max(extract(year FROM holiday_date)) FROM core.holidays) <> 2030 THEN
    RAISE EXCEPTION 'T72: o seed deve cobrir 2026 a 2030';
  END IF;
  -- as 9 datas fixas existem em todos os anos
  IF (SELECT count(*) FROM core.holidays WHERE to_char(holiday_date, 'MM-DD') IN
        ('01-01', '04-21', '05-01', '09-07', '10-12', '11-02', '11-15', '11-20', '12-25')) <> 45 THEN
    RAISE EXCEPTION 'T72: faltam datas fixas nacionais';
  END IF;
  -- Carnaval e Corpus Christi (ponto facultativo) não entram
  IF EXISTS (SELECT 1 FROM core.holidays WHERE name ILIKE '%carnaval%' OR name ILIKE '%corpus%') THEN
    RAISE EXCEPTION 'T72: Carnaval e Corpus Christi nao entram no seed nacional';
  END IF;
END $$;

-- idempotência: reexecutar o seed (o mesmo arquivo da migration) mantém 50 linhas. Como crm_owner, sob FORCE.
SET LOCAL ROLE crm_owner;
\ir ../migrations/V018__core_holidays_seed_2026_2030.sql
\ir ../migrations/V018__core_holidays_seed_2026_2030.sql
RESET ROLE;
DO $$
BEGIN
  IF (SELECT count(*) FROM core.holidays) <> 50 THEN
    RAISE EXCEPTION 'T72: reexecutar o seed nao pode duplicar (obtido %)', (SELECT count(*) FROM core.holidays);
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- T69 (parte): holidays é só leitura para a aplicação
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T69 dentista le os feriados nacionais', 'SELECT 1 FROM core.holidays', 50);
SELECT pg_temp.expect_err('T69 dentista nao insere em holidays', '42501',
  $q$INSERT INTO core.holidays (holiday_date, name) VALUES (DATE '2031-01-01', 'Confraternização Universal')$q$);
SELECT pg_temp.expect_err('T69 dentista nao altera holidays', '42501', $q$UPDATE core.holidays SET name = 'x'$q$);
SELECT pg_temp.expect_err('T69 dentista nao apaga holidays', '42501', 'DELETE FROM core.holidays');
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T69 recepcao le os feriados nacionais', 'SELECT 1 FROM core.holidays', 50);
SELECT pg_temp.expect_err('T69 recepcao nao insere em holidays', '42501',
  $q$INSERT INTO core.holidays (holiday_date, name) VALUES (DATE '2031-01-01', 'Confraternização Universal')$q$);
-- dado global e público: legível mesmo sem sessão válida
:ctx_none \gset
SELECT pg_temp.expect_rows('T67 holidays legivel sem app.clinic_id nem app.role', 'SELECT 1 FROM core.holidays', 50);
RESET SESSION AUTHORIZATION;

-- crm_owner consegue INSERT sob FORCE (as migrations de seed futuras dependem disso) e é desfeito no ROLLBACK
SET LOCAL ROLE crm_owner;
INSERT INTO core.holidays (holiday_date, name) VALUES (DATE '2031-01-01', 'Confraternização Universal');
RESET ROLE;
DO $$
BEGIN
  IF (SELECT count(*) FROM core.holidays) <> 51 THEN RAISE EXCEPTION 'T69: crm_owner deveria inserir em holidays sob FORCE'; END IF;
END $$;
SAVEPOINT s_hol;
SET LOCAL ROLE crm_owner;
SELECT pg_temp.expect_err('T71 nome curto em holidays', '23514', $q$INSERT INTO core.holidays (holiday_date, name) VALUES (DATE '2031-02-02', 'x')$q$);
SELECT pg_temp.expect_err('T71 data repetida em holidays', '23505', $q$INSERT INTO core.holidays (holiday_date, name) VALUES (DATE '2031-01-01', 'Outro nome')$q$);
RESET ROLE;
ROLLBACK TO SAVEPOINT s_hol;

-- ---------------------------------------------------------------------
-- T71 e T69 (parte): clinic_holidays
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T71 dentista cadastra o aniversario da cidade', pg_temp.chol_sql(:'ca', :'ua_d', '2031-06-24', 'Aniversario da cidade'), 1);
SELECT pg_temp.expect_rows('T71 a clinica pode repetir uma data nacional', pg_temp.chol_sql(:'ca', :'ua_d', '2031-01-01', 'Confraternização (clínica)'), 1);
SELECT pg_temp.expect_err('T71 data repetida na mesma clinica', '23505', pg_temp.chol_sql(:'ca', :'ua_d', '2031-06-24', 'Outro nome'));
SELECT pg_temp.expect_err('T71 data anterior a 2000', '23514', pg_temp.chol_sql(:'ca', :'ua_d', '1999-12-31', 'Muito antigo'));
SELECT pg_temp.expect_rows('T71 data de 2000-01-01 passa', pg_temp.chol_sql(:'ca', :'ua_d', '2000-01-01', 'Limite'), 1);
SELECT pg_temp.expect_err('T71 nome curto', '23514', pg_temp.chol_sql(:'ca', :'ua_d', '2031-07-01', 'x'));
SELECT pg_temp.expect_err('T71 nome so com espacos', '23514', pg_temp.chol_sql(:'ca', :'ua_d', '2031-07-01', '   '));
:ctx_b_d \gset
SELECT pg_temp.expect_rows('T71 a mesma data em outra clinica passa', pg_temp.chol_sql(:'cb', :'ub_d', '2031-06-24', 'Aniversario da cidade'), 1);
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T69 dentista renomeia', format('UPDATE core.clinic_holidays SET name = %L WHERE holiday_date = %L', 'Aniversario de Piracicaba', '2031-06-24'), 1);
SELECT pg_temp.expect_err('T69 clinic_id e imutavel', '23514', format('UPDATE core.clinic_holidays SET clinic_id = %L WHERE holiday_date = %L', :'cb', '2031-06-24'));
SELECT pg_temp.expect_err('T68 dentista insere em nome da recepcao', '42501', pg_temp.chol_sql(:'ca', :'ua_r', '2031-08-01', 'Feriado'));

:ctx_a_r \gset
SELECT pg_temp.expect_rows('T69 recepcao le os feriados da clinica A', 'SELECT 1 FROM core.clinic_holidays', 3);
SELECT pg_temp.expect_err('T69 recepcao nao insere feriado da clinica', '42501', pg_temp.chol_sql(:'ca', :'ua_r', '2031-08-01', 'Feriado'));
SELECT pg_temp.expect_rows('T69 recepcao nao altera feriado (0 linhas)', format('UPDATE core.clinic_holidays SET name = %L WHERE holiday_date = %L', 'Hackeado', '2031-06-24'), 0);
SELECT pg_temp.expect_rows('T69 recepcao nao remove feriado (0 linhas)', 'DELETE FROM core.clinic_holidays', 0);

:ctx_b_d \gset
SELECT pg_temp.expect_rows('T69 dentista de B nao remove feriado de A (so o seu)', format('DELETE FROM core.clinic_holidays WHERE holiday_date = %L', '2031-06-24'), 1);
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T69 a clinica A ainda tem os 3 feriados', 'SELECT 1 FROM core.clinic_holidays', 3);
-- só o dentista remove (DELETE físico: a única exceção da aplicação à regra "sem DELETE")
SELECT pg_temp.expect_rows('T69 dentista remove um feriado da clinica', format('DELETE FROM core.clinic_holidays WHERE holiday_date = %L', '2031-06-24'), 1);
SELECT pg_temp.expect_rows('T69 restam 2 feriados em A', 'SELECT 1 FROM core.clinic_holidays', 2);

RESET SESSION AUTHORIZATION;
ROLLBACK;
\o
SELECT 'holidays: OK' AS resultado;
