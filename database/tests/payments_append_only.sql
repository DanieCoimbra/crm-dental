-- =====================================================================
-- tests/payments_append_only.sql | Fase 3 / F05-payments
-- Teste 106 da spec global da Fase 3: payments é APPEND-ONLY por três camadas. (1) crm_app só tem SELECT e INSERT (sem UPDATE, DELETE,
-- TRUNCATE); (2) a RLS FORCE não dá ao dono (crm_owner) nenhuma política, então UPDATE/DELETE dele afetam 0 linhas; (3) o trigger
-- nega UPDATE, DELETE e TRUNCATE a quem consegue alcançar a linha (superusuário/BYPASSRLS) e ao TRUNCATE do dono.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 42501 (privilégio, trigger de somente inserção); 0 linhas afetadas sem erro para o dono sob RLS.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

\set bA 'f4000000-0000-4000-8000-000000000701'
\set iA 'f4000000-0000-4000-8000-000000000702'
\set pA 'f4000000-0000-4000-8000-000000000703'

:ctx_a_d \gset
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'pa', :'pt1', :'bA', :'iA', :'pA');

-- (1) como crm_app, dentista
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T106 dentista le o pagamento', format('SELECT 1 FROM core.payments WHERE id = %L', :'pA'), 1);
SELECT pg_temp.expect_err('T106 crm_app nao faz UPDATE', '42501', format('UPDATE core.payments SET amount = 1 WHERE id = %L', :'pA'));
SELECT pg_temp.expect_err('T106 crm_app nao faz UPDATE de outra coluna', '42501', format('UPDATE core.payments SET paid_on = DATE ''2031-01-01'' WHERE id = %L', :'pA'));
SELECT pg_temp.expect_err('T106 crm_app nao faz DELETE', '42501', format('DELETE FROM core.payments WHERE id = %L', :'pA'));
SELECT pg_temp.expect_err('T106 crm_app nao faz TRUNCATE', '42501', 'TRUNCATE core.payments');
RESET SESSION AUTHORIZATION;

-- (2) como crm_owner: a RLS FORCE sem política nega o acesso (0 linhas, sem erro); TRUNCATE não passa por RLS e o trigger o nega
SET LOCAL ROLE crm_owner;
SELECT pg_temp.expect_rows('T106 dono nao enxerga linhas (RLS FORCE sem politica)', format('SELECT 1 FROM core.payments WHERE id = %L', :'pA'), 0);
SELECT pg_temp.expect_rows('T106 UPDATE do dono afeta 0 linhas', format('UPDATE core.payments SET amount = 1 WHERE id = %L', :'pA'), 0);
SELECT pg_temp.expect_rows('T106 DELETE do dono afeta 0 linhas', format('DELETE FROM core.payments WHERE id = %L', :'pA'), 0);
SELECT pg_temp.expect_err('T106 TRUNCATE do dono e negado pelo trigger', '42501', 'TRUNCATE core.payments');
RESET ROLE;

-- (3) como administrador (superusuário ignora a RLS): o trigger de linha nega UPDATE e DELETE; o de comando nega TRUNCATE
SELECT pg_temp.expect_err('T106 UPDATE de superusuario negado pelo trigger', '42501', format('UPDATE core.payments SET amount = 1 WHERE id = %L', :'pA'));
SELECT pg_temp.expect_err('T106 DELETE de superusuario negado pelo trigger', '42501', format('DELETE FROM core.payments WHERE id = %L', :'pA'));
SELECT pg_temp.expect_err('T106 TRUNCATE de superusuario negado pelo trigger', '42501', 'TRUNCATE core.payments');
SET CONSTRAINTS ALL IMMEDIATE;
SELECT pg_temp.expect_err('T106 TRUNCATE ... CASCADE vindo de installments tambem e negado', '42501', 'TRUNCATE core.installments CASCADE');
-- nada mudou
DO $$
BEGIN
  IF (SELECT amount FROM core.payments WHERE id = 'f4000000-0000-4000-8000-000000000703') <> 100.00 THEN
    RAISE EXCEPTION 'T106: o pagamento foi alterado';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'payments_append_only: OK' AS resultado;
