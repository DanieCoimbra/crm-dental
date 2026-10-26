-- =====================================================================
-- tests/procedure_types.sql | Fase 3 / F01-procedure-types
-- Testes 88, 89 e 90 da spec global da Fase 3: CHECKs do catálogo, unicidade do nome entre os ativos (sem diferenciar caixa), papéis
-- (recepção lê e não grava; dentista grava; ninguém apaga) e a FK composta de appointments.procedure_type_id.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 23514 CHECK ou coluna imutável; 23505 nome ativo repetido; 23503 FK; 42501 papel, autoria ou falta de privilégio;
--            linha invisível para alterar = 0 linhas afetadas, sem erro.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set n1 'f4000000-0000-4000-8000-000000000101'
\set n2 'f4000000-0000-4000-8000-000000000102'
\set n3 'f4000000-0000-4000-8000-000000000103'
\set n4 'f4000000-0000-4000-8000-000000000104'
\set ap1 'f4000000-0000-4000-8000-000000000111'
\set ap2 'f4000000-0000-4000-8000-000000000112'
\set ap3 'f4000000-0000-4000-8000-000000000113'

SET SESSION AUTHORIZATION crm_app;

-- ---------------------------------------------------------------------
-- T89: papéis (antes de criar linhas novas: a recepção da clínica A enxerga exatamente os 2 procedimentos do seed)
-- ---------------------------------------------------------------------
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T89 recepcao le os procedimentos da propria clinica', 'SELECT 1 FROM core.procedure_types', 2);
SELECT pg_temp.expect_err('T89 recepcao nao insere', '42501', pg_temp.ptype_sql(:'n1', :'ca', 'Limpeza', '100', '30', '#112233', :'ua_r'));
SELECT pg_temp.expect_rows('T89 recepcao nao altera (0 linhas)', format('UPDATE core.procedure_types SET base_price = 1 WHERE id = %L', :'pt1'), 0);
SELECT pg_temp.expect_err('T89 crm_app nao tem DELETE', '42501', format('DELETE FROM core.procedure_types WHERE id = %L', :'pt1'));

:ctx_a_d \gset
SELECT pg_temp.expect_rows('T89 dentista le', 'SELECT 1 FROM core.procedure_types', 2);
SELECT pg_temp.expect_rows('T89 dentista insere', pg_temp.ptype_sql(:'n1', :'ca', 'Limpeza', '100.50', '30', '#112233', :'ua_d'), 1);
SELECT pg_temp.expect_rows('T89 dentista altera o preco', format('UPDATE core.procedure_types SET base_price = 120 WHERE id = %L', :'n1'), 1);
SELECT pg_temp.expect_err('T89 dentista nao apaga', '42501', format('DELETE FROM core.procedure_types WHERE id = %L', :'n1'));
SELECT pg_temp.expect_err('T89 autoria: created_by diferente de app.user_id', '42501',
  pg_temp.ptype_sql(:'n2', :'ca', 'Outro', '10', '30', '#112233', :'ua_r'));
SELECT pg_temp.expect_err('T89 created_at imutavel', '23514', format('UPDATE core.procedure_types SET created_at = now() - interval ''1 day'' WHERE id = %L', :'n1'));
SELECT pg_temp.expect_err('T89 created_by imutavel', '23514', format('UPDATE core.procedure_types SET created_by = %L WHERE id = %L', :'ua_r', :'n1'));

-- sem papel ou com papel inválido: tudo negado
:ctx_a_norole \gset
SELECT pg_temp.expect_rows('T89 sem papel: 0 linhas', 'SELECT 1 FROM core.procedure_types', 0);
SELECT pg_temp.expect_err('T89 sem papel: INSERT falha', '42501', pg_temp.ptype_sql(:'n2', :'ca', 'Outro', '10', '30', '#112233', :'ua_d'));
:ctx_a_d \gset
SELECT set_config('app.role', 'ADMIN', true) AS _ \gset
SELECT pg_temp.expect_rows('T89 papel ADMIN (inexistente): 0 linhas', 'SELECT 1 FROM core.procedure_types', 0);
:ctx_none \gset
SELECT pg_temp.expect_rows('T89 sem sessao: 0 linhas', 'SELECT 1 FROM core.procedure_types', 0);

-- ---------------------------------------------------------------------
-- T88: CHECKs e unicidade
-- ---------------------------------------------------------------------
:ctx_a_d \gset
SELECT pg_temp.expect_err('T88 preco negativo', '23514', pg_temp.ptype_sql(:'n2', :'ca', 'Preco neg', '-0.01', '30', '#112233', :'ua_d'));
SELECT pg_temp.expect_err('T88 preco acima do teto', '23514', pg_temp.ptype_sql(:'n2', :'ca', 'Preco alto', '1000000', '30', '#112233', :'ua_d'));
SELECT pg_temp.expect_rows('T88 preco zero e teto passam', pg_temp.ptype_sql(:'n2', :'ca', 'Cortesia', '0', '5', '#112233', :'ua_d'), 1);
SELECT pg_temp.expect_rows('T88 preco no teto passa', pg_temp.ptype_sql(gen_random_uuid()::text, :'ca', 'Implante', '999999.99', '720', '#aabbcc', :'ua_d'), 1);
SELECT pg_temp.expect_err('T88 duracao 7', '23514', pg_temp.ptype_sql(:'n3', :'ca', 'Dur7', '10', '7', '#112233', :'ua_d'));
SELECT pg_temp.expect_err('T88 duracao 4', '23514', pg_temp.ptype_sql(:'n3', :'ca', 'Dur4', '10', '4', '#112233', :'ua_d'));
SELECT pg_temp.expect_err('T88 duracao 725', '23514', pg_temp.ptype_sql(:'n3', :'ca', 'Dur725', '10', '725', '#112233', :'ua_d'));
SELECT pg_temp.expect_err('T88 duracao 0', '23514', pg_temp.ptype_sql(:'n3', :'ca', 'Dur0', '10', '0', '#112233', :'ua_d'));
SELECT pg_temp.expect_err('T88 cor red', '23514', pg_temp.ptype_sql(:'n3', :'ca', 'CorRed', '10', '30', 'red', :'ua_d'));
SELECT pg_temp.expect_err('T88 cor curta', '23514', pg_temp.ptype_sql(:'n3', :'ca', 'CorCurta', '10', '30', '#12345', :'ua_d'));
SELECT pg_temp.expect_err('T88 cor nao hexadecimal', '23514', pg_temp.ptype_sql(:'n3', :'ca', 'CorGG', '10', '30', '#GGGGGG', :'ua_d'));
SELECT pg_temp.expect_err('T88 nome em branco', '23514', pg_temp.ptype_sql(:'n3', :'ca', '   ', '10', '30', '#112233', :'ua_d'));
SELECT pg_temp.expect_err('T88 nome de 1 caractere', '23514', pg_temp.ptype_sql(:'n3', :'ca', 'a', '10', '30', '#112233', :'ua_d'));

-- unicidade entre os ativos, sem diferenciar caixa
SELECT pg_temp.expect_err('T88 nome ativo repetido (caixa diferente)', '23505', pg_temp.ptype_sql(:'n3', :'ca', 'RESTAURACAO', '10', '30', '#112233', :'ua_d'));
SELECT pg_temp.expect_err('T88 nome ativo repetido (exato)', '23505', pg_temp.ptype_sql(:'n3', :'ca', 'Canal', '10', '30', '#112233', :'ua_d'));
-- inativar libera o nome; reativar o antigo, com o nome já tomado, falha
SELECT pg_temp.expect_rows('T88 inativa pt1', format('UPDATE core.procedure_types SET active = false WHERE id = %L', :'pt1'), 1);
SELECT pg_temp.expect_rows('T88 nome inativo e reutilizado', pg_temp.ptype_sql(:'n3', :'ca', 'restauracao', '170', '30', '#112233', :'ua_d'), 1);
SELECT pg_temp.expect_err('T88 reativar o antigo colide', '23505', format('UPDATE core.procedure_types SET active = true WHERE id = %L', :'pt1'));
-- o mesmo nome em outra clínica passa
:ctx_b_d \gset
SELECT pg_temp.expect_rows('T88 B ve so os seus', 'SELECT 1 FROM core.procedure_types', 1);
SELECT pg_temp.expect_rows('T88 mesmo nome em outra clinica passa', pg_temp.ptype_sql(:'n4', :'cb', 'Canal', '500', '60', '#112233', :'ub_d'), 1);

-- ---------------------------------------------------------------------
-- T90: FK composta de appointments.procedure_type_id (F01 / V023)
-- ---------------------------------------------------------------------
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T90 agendamento sem procedimento (nulo) passa',
  pg_temp.appt_sql(:'ap1', :'ca', :'pa', :'ua_d', '2031-08-01 08:00+00', '2031-08-01 08:30+00', 'SCHEDULED'), 1);
SELECT pg_temp.expect_rows('T90 vincula o procedimento da propria clinica',
  format('UPDATE core.appointments SET procedure_type_id = %L WHERE id = %L', :'pt2', :'ap1'), 1);
SELECT pg_temp.expect_err('T90 procedimento de OUTRA clinica falha na FK composta', '23503',
  format('UPDATE core.appointments SET procedure_type_id = %L WHERE id = %L', :'ptb', :'ap1'));
SELECT pg_temp.expect_err('T90 procedimento inexistente falha', '23503',
  format('UPDATE core.appointments SET procedure_type_id = gen_random_uuid() WHERE id = %L', :'ap1'));
SELECT pg_temp.expect_err('T90 INSERT com procedimento de outra clinica falha', '23503', format(
  'INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, procedure_type_id, created_by) '
  'VALUES (%L, %L, %L, ''2031-08-02 08:00+00'', ''2031-08-02 08:30+00'', ''SCHEDULED'', %L, %L)', :'ap2', :'ca', :'pa', :'ptb', :'ua_d'));
SELECT pg_temp.expect_rows('T90 INSERT com procedimento da clinica passa', format(
  'INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, procedure_type_id, created_by) '
  'VALUES (%L, %L, %L, ''2031-08-02 08:00+00'', ''2031-08-02 08:30+00'', ''SCHEDULED'', %L, %L)', :'ap2', :'ca', :'pa', :'pt1', :'ua_d'), 1);
-- a recepção continua remarcando e editando notes sem tocar no procedimento (a regra da Fase 2 não mudou)
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T90 recepcao remarca agendamento com procedimento', format(
  'UPDATE core.appointments SET starts_at = ''2031-08-02 09:00+00'', ends_at = ''2031-08-02 09:30+00'' WHERE id = %L', :'ap2'), 1);
-- o procedimento nunca some: sem DELETE e RESTRICT (nem o administrador apaga com agendamento ligado)
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  BEGIN
    DELETE FROM core.procedure_types WHERE id = 'f4000000-0000-4000-8000-0000000000c1';
    RAISE EXCEPTION 'T90: DELETE de procedimento ligado a agendamento deveria falhar';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
END $$;
ROLLBACK;
\o
SELECT 'procedure_types: OK' AS resultado;
