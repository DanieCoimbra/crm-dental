-- =====================================================================
-- tests/session_functions.sql | Fase 1 / F01-session-foundation
-- Testes: 35 e 53 (parte) da spec global da Fase 1 (funções de sessão, parâmetro de retenção,
-- EXECUTE por papel e extensão pg_trgm).
-- Executar : como administrador do banco de teste, depois do bootstrap (00, 10 e 20) e das migrations.
-- =====================================================================
\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------
-- Privilégios, volatilidade e search_path
-- ---------------------------------------------------------------------
DO $$
DECLARE
  f text;
BEGIN
  FOREACH f IN ARRAY ARRAY['core.current_app_role()', 'core.current_user_id()', 'core.clinical_retention_period()'] LOOP
    IF NOT (has_function_privilege('crm_app', f, 'EXECUTE') AND has_function_privilege('crm_definer', f, 'EXECUTE')) THEN
      RAISE EXCEPTION 'F01: % deve ser executavel por crm_app e crm_definer', f;
    END IF;
    IF has_function_privilege('crm_backup', f, 'EXECUTE') THEN
      RAISE EXCEPTION 'F01: % nao deve ser executavel por crm_backup', f;
    END IF;
    IF EXISTS (SELECT 1 FROM aclexplode((SELECT proacl FROM pg_proc WHERE oid = f::regprocedure)) a WHERE a.grantee = 0) THEN
      RAISE EXCEPTION 'F01: % executavel por PUBLIC', f;
    END IF;
    -- nenhum outro grantee além do dono, de crm_app e de crm_definer (anon, authenticated, service_role, crm_backup...)
    IF EXISTS (SELECT 1 FROM pg_proc p, LATERAL aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                WHERE p.oid = f::regprocedure AND a.grantee <> p.proowner
                  AND a.grantee NOT IN (SELECT oid FROM pg_roles WHERE rolname IN ('crm_app', 'crm_definer'))) THEN
      RAISE EXCEPTION 'F01: % tem EXECUTE para papel alem de crm_app e crm_definer', f;
    END IF;
    IF (SELECT proparallel FROM pg_proc WHERE oid = f::regprocedure) <> 's' THEN
      RAISE EXCEPTION 'F01: % deve ser PARALLEL SAFE', f;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid = f::regprocedure
                      AND 'search_path=pg_catalog' = ANY (p.proconfig)) THEN
      RAISE EXCEPTION 'F01: % deve fixar search_path = pg_catalog', f;
    END IF;
    IF (SELECT prosecdef FROM pg_proc WHERE oid = f::regprocedure) THEN
      RAISE EXCEPTION 'F01: % nao pode ser SECURITY DEFINER', f;
    END IF;
  END LOOP;

  IF (SELECT provolatile FROM pg_proc WHERE oid = 'core.current_app_role()'::regprocedure) <> 's'
     OR (SELECT provolatile FROM pg_proc WHERE oid = 'core.current_user_id()'::regprocedure) <> 's' THEN
    RAISE EXCEPTION 'F01: funcoes de sessao devem ser STABLE';
  END IF;
  IF (SELECT provolatile FROM pg_proc WHERE oid = 'core.clinical_retention_period()'::regprocedure) <> 'i' THEN
    RAISE EXCEPTION 'F01: clinical_retention_period() deve ser IMMUTABLE';
  END IF;

  -- crm_definer executa a função de tenant (políticas escopadas ao tenant das features seguintes)
  IF NOT has_function_privilege('crm_definer', 'core.current_clinic_id()', 'EXECUTE') THEN
    RAISE EXCEPTION 'F01: crm_definer deve executar core.current_clinic_id()';
  END IF;
  IF has_function_privilege('crm_backup', 'core.current_clinic_id()', 'EXECUTE') THEN
    RAISE EXCEPTION 'F01: crm_backup nao deve executar core.current_clinic_id()';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_proc p, LATERAL aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
              WHERE p.oid = 'core.current_clinic_id()'::regprocedure AND a.grantee <> p.proowner
                AND a.grantee NOT IN (SELECT oid FROM pg_roles WHERE rolname IN ('crm_app', 'crm_definer'))) THEN
    RAISE EXCEPTION 'F01: core.current_clinic_id() tem EXECUTE para papel alem de crm_app e crm_definer';
  END IF;

  -- retenção: 20 anos
  IF core.clinical_retention_period() <> interval '20 years' THEN
    RAISE EXCEPTION 'F01: retencao deve ser 20 anos, veio %', core.clinical_retention_period();
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- app.role (teste 35): só os dois valores exatos; o resto é NULL
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_app;
BEGIN;
DO $$
DECLARE
  v text;
  bad text[] := ARRAY['', 'ADMIN', 'dentist_admin', 'Dentist_Admin', ' DENTIST_ADMIN', 'DENTIST_ADMIN ',
                      'receptionist', 'RECEPTIONIST;', 'DENTIST_ADMIN,RECEPTIONIST'];
BEGIN
  IF core.current_app_role() IS NOT NULL THEN
    RAISE EXCEPTION 'F01: sem app.role a funcao deve devolver NULL';
  END IF;
  FOREACH v IN ARRAY bad LOOP
    PERFORM set_config('app.role', v, true);
    IF core.current_app_role() IS NOT NULL THEN
      RAISE EXCEPTION 'F01: app.role=% deve devolver NULL', quote_literal(v);
    END IF;
  END LOOP;
  PERFORM set_config('app.role', 'DENTIST_ADMIN', true);
  IF core.current_app_role() IS DISTINCT FROM 'DENTIST_ADMIN' THEN
    RAISE EXCEPTION 'F01: DENTIST_ADMIN nao reconhecido';
  END IF;
  PERFORM set_config('app.role', 'RECEPTIONIST', true);
  IF core.current_app_role() IS DISTINCT FROM 'RECEPTIONIST' THEN
    RAISE EXCEPTION 'F01: RECEPTIONIST nao reconhecido';
  END IF;
END $$;
ROLLBACK;

-- ---------------------------------------------------------------------
-- app.user_id: NULL sem valor; uuid com valor; 22P02 se não for UUID
-- ---------------------------------------------------------------------
BEGIN;
DO $$
DECLARE
  caught boolean := false;
BEGIN
  IF core.current_user_id() IS NOT NULL THEN
    RAISE EXCEPTION 'F01: sem app.user_id a funcao deve devolver NULL';
  END IF;
  PERFORM set_config('app.user_id', '', true);
  IF core.current_user_id() IS NOT NULL THEN
    RAISE EXCEPTION 'F01: app.user_id vazio deve devolver NULL';
  END IF;
  PERFORM set_config('app.user_id', '33333333-3333-3333-3333-333333333333', true);
  IF core.current_user_id() IS DISTINCT FROM '33333333-3333-3333-3333-333333333333'::uuid THEN
    RAISE EXCEPTION 'F01: app.user_id valido nao reconhecido';
  END IF;
  PERFORM set_config('app.user_id', 'abc', true);
  BEGIN
    PERFORM core.current_user_id();
  EXCEPTION WHEN invalid_text_representation THEN
    caught := true;
  END;
  IF NOT caught THEN
    RAISE EXCEPTION 'F01: app.user_id invalido deve gerar 22P02';
  END IF;
END $$;
ROLLBACK;

-- set_config local: após o fim da transação, as variáveis voltam vazias na mesma conexão
BEGIN;
SELECT set_config('app.role', 'DENTIST_ADMIN', true) AS _ \gset
SELECT set_config('app.user_id', '33333333-3333-3333-3333-333333333333', true) AS _ \gset
COMMIT;
DO $$
BEGIN
  IF core.current_app_role() IS NOT NULL OR core.current_user_id() IS NOT NULL THEN
    RAISE EXCEPTION 'F01: app.role/app.user_id vazaram apos o COMMIT (set_config nao foi local)';
  END IF;
END $$;
RESET SESSION AUTHORIZATION;

-- ---------------------------------------------------------------------
-- crm_definer executa a função de tenant e as de sessão (sem tenant: NULL)
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_definer;
DO $$
BEGIN
  IF core.current_clinic_id() IS NOT NULL OR core.current_app_role() IS NOT NULL OR core.current_user_id() IS NOT NULL THEN
    RAISE EXCEPTION 'F01: crm_definer sem sessao deve receber NULL';
  END IF;
END $$;
RESET SESSION AUTHORIZATION;

-- ---------------------------------------------------------------------
-- Extensão pg_trgm (bootstrap 20_extensions.sql)
-- ---------------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                  WHERE e.extname = 'pg_trgm' AND n.nspname = 'extensions') THEN
    RAISE EXCEPTION 'F01: pg_trgm deve estar instalada no schema extensions';
  END IF;
  IF NOT has_schema_privilege('crm_owner', 'extensions', 'USAGE') THEN
    RAISE EXCEPTION 'F01: crm_owner deve ter USAGE em extensions';
  END IF;
  IF has_schema_privilege('crm_app', 'extensions', 'USAGE')
     OR has_schema_privilege('crm_backup', 'extensions', 'USAGE')
     OR has_schema_privilege('crm_definer', 'extensions', 'USAGE') THEN
    RAISE EXCEPTION 'F01: crm_app, crm_backup e crm_definer nao devem ter USAGE em extensions';
  END IF;
END $$;

-- crm_owner consegue criar o índice trigram (sonda dentro de transação revertida)
SET SESSION AUTHORIZATION crm_owner;
BEGIN;
CREATE TABLE core.t_trgm_probe_f01 (name varchar(120));
CREATE INDEX ix_t_trgm_probe_f01 ON core.t_trgm_probe_f01 USING gin (lower(name) extensions.gin_trgm_ops);
ROLLBACK;
RESET SESSION AUTHORIZATION;

-- crm_app usa o índice trigram sem USAGE em extensions: o operador LIKE basta
SET SESSION AUTHORIZATION crm_owner;
BEGIN;
CREATE TABLE core.t_trgm_use_f01 (name varchar(120));
CREATE INDEX ix_t_trgm_use_f01 ON core.t_trgm_use_f01 USING gin (lower(name) extensions.gin_trgm_ops);
INSERT INTO core.t_trgm_use_f01 SELECT 'paciente ' || g FROM generate_series(1, 2000) g;
INSERT INTO core.t_trgm_use_f01 VALUES ('maria da silva');
ANALYZE core.t_trgm_use_f01;
GRANT SELECT ON core.t_trgm_use_f01 TO crm_app;
SET LOCAL enable_seqscan = off;
SET LOCAL SESSION AUTHORIZATION crm_app;
DO $$
DECLARE
  n int;
  plan text;
BEGIN
  SELECT count(*) INTO n FROM core.t_trgm_use_f01 WHERE lower(name) LIKE '%silv%';
  IF n <> 1 THEN
    RAISE EXCEPTION 'F01: busca LIKE por trecho devolveu % linhas (esperado 1)', n;
  END IF;
  FOR plan IN EXECUTE 'EXPLAIN SELECT 1 FROM core.t_trgm_use_f01 WHERE lower(name) LIKE ''%silv%''' LOOP
    IF plan LIKE '%ix_t_trgm_use_f01%' THEN RETURN; END IF;
  END LOOP;
  RAISE EXCEPTION 'F01: o plano de crm_app nao usou o indice trigram';
END $$;
ROLLBACK;
RESET SESSION AUTHORIZATION;

\echo 'session_functions: OK'
