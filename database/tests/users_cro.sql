-- =====================================================================
-- tests/users_cro.sql | Fase 4 / F01-professional-cro (testes 125 e 126)
--   125  CHECKs do CRO: UF fora da lista, número com letras ou com 16 dígitos, par incompleto e recepcionista com CRO (23514); o
--        próprio dentista grava um CRO válido e pode apagá-lo (os dois nulos)
--   126  só o próprio usuário altera o CRO (42501 para outro usuário, inclusive sem sessão); as demais atualizações de users seguem
--        permitidas; a outra clínica não enxerga a linha (0 linhas)
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 23514 CHECK; 42501 trigger guard_user_cro.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase4_seed.sql

-- semente: o CRO do dentista A veio preenchido pelo seed
DO $$
BEGIN
  IF (SELECT cro_number || cro_state FROM core.users WHERE id = 'f1000000-0000-4000-8000-0000000000a1') <> '12345SP' THEN
    RAISE EXCEPTION 'T125: CRO do seed ausente';
  END IF;
END $$;

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T125 UF fora da lista', '23514', format('UPDATE core.users SET cro_number = ''111'', cro_state = ''XX'' WHERE id = %L', :'ua_d'));
SELECT pg_temp.expect_err('T125 UF em minuscula', '23514', format('UPDATE core.users SET cro_number = ''111'', cro_state = ''sp'' WHERE id = %L', :'ua_d'));
SELECT pg_temp.expect_err('T125 numero com letras', '23514', format('UPDATE core.users SET cro_number = ''12A'', cro_state = ''SP'' WHERE id = %L', :'ua_d'));
SELECT pg_temp.expect_err('T125 numero com 16 digitos (varchar(15))', '22001', format('UPDATE core.users SET cro_number = ''1234567890123456'', cro_state = ''SP'' WHERE id = %L', :'ua_d'));
SELECT pg_temp.expect_err('T125 numero vazio', '23514', format('UPDATE core.users SET cro_number = '''', cro_state = ''SP'' WHERE id = %L', :'ua_d'));
SELECT pg_temp.expect_err('T125 so o numero (par incompleto)', '23514', format('UPDATE core.users SET cro_state = NULL WHERE id = %L', :'ua_d'));
SELECT pg_temp.expect_err('T125 so a UF (par incompleto)', '23514', format('UPDATE core.users SET cro_number = NULL WHERE id = %L', :'ua_d'));
SELECT pg_temp.expect_rows('T125 dentista grava CRO valido de 15 digitos',
  format('UPDATE core.users SET cro_number = ''123456789012345'', cro_state = ''MG'' WHERE id = %L', :'ua_d'), 1);
SELECT pg_temp.expect_rows('T125 dentista apaga o CRO (os dois nulos)',
  format('UPDATE core.users SET cro_number = NULL, cro_state = NULL WHERE id = %L', :'ua_d'), 1);
SELECT pg_temp.expect_rows('T125 dentista restaura o CRO',
  format('UPDATE core.users SET cro_number = ''12345'', cro_state = ''SP'' WHERE id = %L', :'ua_d'), 1);

-- recepcionista com CRO: o dono da linha (sessão da própria recepcionista) passa pela guarda e a CHECK recusa
:ctx_a_r \gset
SELECT pg_temp.expect_err('T125 recepcionista com CRO', '23514', format('UPDATE core.users SET cro_number = ''1'', cro_state = ''SP'' WHERE id = %L', :'ua_r'));

-- 126: outro usuário
:ctx_a_r \gset
SELECT pg_temp.expect_err('T126 recepcionista altera o CRO do dentista', '42501', format('UPDATE core.users SET cro_number = ''999'', cro_state = ''SP'' WHERE id = %L', :'ua_d'));
SELECT pg_temp.expect_err('T126 recepcionista apaga o CRO do dentista', '42501', format('UPDATE core.users SET cro_number = NULL, cro_state = NULL WHERE id = %L', :'ua_d'));
:ctx_a_d \gset
SELECT pg_temp.expect_err('T126 dentista altera o CRO de outro usuario (recepcionista)', '42501', format('UPDATE core.users SET cro_number = ''1'', cro_state = ''SP'' WHERE id = %L', :'ua_r'));
SELECT pg_temp.expect_rows('T126 dentista altera o nome de outro usuario (permitido)', format('UPDATE core.users SET name = ''Recep A Renomeada'' WHERE id = %L', :'ua_r'), 1);
SELECT pg_temp.expect_rows('T126 dentista altera o proprio nome sem tocar no CRO', format('UPDATE core.users SET name = ''Dentista A Renomeado'' WHERE id = %L', :'ua_d'), 1);
-- outra clínica: a RLS esconde a linha
:ctx_b_d \gset
SELECT pg_temp.expect_rows('T126 dentista de B nao enxerga nem altera o usuario de A', format('UPDATE core.users SET cro_number = ''1'', cro_state = ''SP'' WHERE id = %L', :'ua_d'), 0);
SELECT pg_temp.expect_rows('T126 dentista de B nao le o CRO do usuario de A', format('SELECT 1 FROM core.users WHERE id = %L', :'ua_d'), 0);
RESET SESSION AUTHORIZATION;

-- sem sessão (nenhum app.user_id): nem o administrador altera o CRO de alguém
:ctx_none \gset
SELECT pg_temp.expect_err('T126 sem sessao nao altera o CRO', '42501', format('UPDATE core.users SET cro_number = ''777'', cro_state = ''SP'' WHERE id = %L', :'ua_d'));
:ctx_a_d_user_b \gset
SELECT pg_temp.expect_err('T126 sessao de outro usuario nao altera o CRO', '42501', format('UPDATE core.users SET cro_number = ''777'', cro_state = ''SP'' WHERE id = %L', :'ua_d'));

-- nada mudou
DO $$
BEGIN
  IF (SELECT cro_number || cro_state FROM core.users WHERE id = 'f1000000-0000-4000-8000-0000000000a1') <> '12345SP' THEN
    RAISE EXCEPTION 'T126: o CRO do dentista foi alterado';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'users_cro: OK' AS resultado;
