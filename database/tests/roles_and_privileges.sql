-- =====================================================================
-- tests/roles_and_privileges.sql | F01-roles-and-bootstrap
-- Testes: 17, 19, 20, 21 (spec global do banco, seção 12) + privilégios de schema.
-- Executar : como administrador do banco de teste, DEPOIS do bootstrap e da V001.
--            psql -v ON_ERROR_STOP=1 -f roles_and_privileges.sql
-- Usa SET SESSION AUTHORIZATION para agir como crm_app (o SET ROLE seguinte é
-- verificado contra o usuário de sessão, não contra o administrador).
-- =====================================================================
\set ON_ERROR_STOP on

-- T17: nenhum papel do CRM é privilegiado ---------------------------------
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT rolname FROM pg_roles
     WHERE rolname IN ('crm_owner', 'crm_app', 'crm_backup', 'crm_definer')
       AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication)
  LOOP
    RAISE EXCEPTION 'T17: papel % e privilegiado', r.rolname;
  END LOOP;
  IF (SELECT count(*) FROM pg_roles WHERE rolname IN ('crm_owner','crm_app','crm_backup','crm_definer')) <> 4 THEN
    RAISE EXCEPTION 'T17: faltam papeis do CRM';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_definer' AND rolcanlogin) THEN
    RAISE EXCEPTION 'T17: crm_definer nao pode ter LOGIN';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_auth_members am
               JOIN pg_roles m ON m.oid = am.member JOIN pg_roles g ON g.oid = am.roleid
              WHERE m.rolname IN ('crm_app','crm_backup')) THEN
    RAISE EXCEPTION 'T17: crm_app/crm_backup nao podem ser membros de nenhum papel';
  END IF;
END $$;

-- Dono e privilégios do schema core ---------------------------------------
DO $$
DECLARE r text;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_namespace n JOIN pg_roles o ON o.oid = n.nspowner
                  WHERE n.nspname = 'core' AND o.rolname = 'crm_owner') THEN
    RAISE EXCEPTION 'schema core deve pertencer a crm_owner';
  END IF;
  FOREACH r IN ARRAY ARRAY['crm_app', 'crm_backup', 'crm_definer'] LOOP
    IF NOT has_schema_privilege(r, 'core', 'USAGE') THEN
      RAISE EXCEPTION '% deve ter USAGE em core', r;
    END IF;
    IF has_schema_privilege(r, 'core', 'CREATE') THEN
      RAISE EXCEPTION '% nao pode ter CREATE em core', r;
    END IF;
  END LOOP;
  -- Nenhuma concessão para PUBLIC (grantee 0) no schema core
  IF EXISTS (SELECT 1 FROM pg_namespace n, LATERAL aclexplode(n.nspacl) a
              WHERE n.nspname = 'core' AND a.grantee = 0) THEN
    RAISE EXCEPTION 'core nao pode ter privilegios para PUBLIC';
  END IF;
END $$;

-- T21: papéis internos do Supabase sem acesso ao core ----------------------
DO $$
DECLARE r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['anon', 'authenticated', 'service_role'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      IF has_schema_privilege(r, 'core', 'USAGE') OR has_schema_privilege(r, 'core', 'CREATE') THEN
        RAISE EXCEPTION 'T21: % tem acesso ao schema core', r;
      END IF;
    END IF;
  END LOOP;
END $$;

-- Parâmetros de sessão do crm_app e do crm_backup --------------------------
DO $$
DECLARE cfg text[];
BEGIN
  SELECT s.setconfig INTO cfg FROM pg_db_role_setting s
    JOIN pg_roles r ON r.oid = s.setrole WHERE r.rolname = 'crm_app' AND s.setdatabase = 0;
  IF cfg IS NULL
     OR NOT ('statement_timeout=15s' = ANY (cfg))
     OR NOT ('idle_in_transaction_session_timeout=30s' = ANY (cfg))
     OR NOT ('search_path=core, pg_catalog' = ANY (cfg)) THEN
    RAISE EXCEPTION 'parametros de sessao do crm_app incorretos: %', cfg;
  END IF;
  SELECT s.setconfig INTO cfg FROM pg_db_role_setting s
    JOIN pg_roles r ON r.oid = s.setrole WHERE r.rolname = 'crm_backup' AND s.setdatabase = 0;
  IF cfg IS NULL OR NOT ('statement_timeout=300s' = ANY (cfg)) THEN
    RAISE EXCEPTION 'parametros de sessao do crm_backup incorretos: %', cfg;
  END IF;
END $$;

-- T19: crm_app não enxerga objetos novos do crm_owner ----
BEGIN;
SET LOCAL ROLE crm_owner;
CREATE TABLE core.t_probe_f01 (id integer);
CREATE FUNCTION core.f_probe_f01() RETURNS integer LANGUAGE sql AS 'SELECT 1';
RESET ROLE;

SET SESSION AUTHORIZATION crm_app;
DO $$
BEGIN
  BEGIN
    PERFORM 1 FROM core.t_probe_f01;
    RAISE EXCEPTION 'T19: crm_app leu tabela nova do crm_owner';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    PERFORM core.f_probe_f01();
    RAISE EXCEPTION 'T19: funcao nova ficou executavel por PUBLIC';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
RESET SESSION AUTHORIZATION;
ROLLBACK;

-- T20 e DDL: crm_app não assume outros papéis nem cria objetos --------------
SET SESSION AUTHORIZATION crm_app;
DO $$
DECLARE r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['crm_definer', 'crm_owner', 'crm_backup', 'postgres'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      BEGIN
        EXECUTE format('SET ROLE %I', r);
        RAISE EXCEPTION 'T20: crm_app conseguiu SET ROLE %', r;
      EXCEPTION WHEN insufficient_privilege THEN NULL;
      END;
    END IF;
  END LOOP;

  BEGIN
    EXECUTE 'CREATE TABLE core.t_ddl_f01 (id integer)';
    RAISE EXCEPTION 'DDL: crm_app criou tabela em core';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    EXECUTE 'CREATE TABLE public.t_ddl_f01 (id integer)';
    RAISE EXCEPTION 'DDL: crm_app criou tabela em public';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    EXECUTE 'CREATE SCHEMA s_ddl_f01';
    RAISE EXCEPTION 'DDL: crm_app criou schema';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
RESET SESSION AUTHORIZATION;

-- crm_backup também não assume papéis nem cria objetos ---------------------------
SET SESSION AUTHORIZATION crm_backup;
DO $$
BEGIN
  BEGIN
    EXECUTE 'SET ROLE crm_owner';
    RAISE EXCEPTION 'crm_backup conseguiu SET ROLE crm_owner';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    EXECUTE 'CREATE TABLE core.t_ddl_f01 (id integer)';
    RAISE EXCEPTION 'DDL: crm_backup criou tabela em core';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
RESET SESSION AUTHORIZATION;

SELECT 'roles_and_privileges: OK' AS resultado;
