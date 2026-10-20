-- =====================================================================
-- F01-roles-and-bootstrap | 00_roles_and_schema.sql
-- Objetivo : criar os papéis (crm_definer, crm_owner, crm_app, crm_backup),
--            o schema core e os parâmetros de sessão dos papéis.
-- Executar : UMA vez (idempotente), como administrador do projeto (postgres),
--            via workflow db-bootstrap.yml. NUNCA pela API.
-- Variáveis: crm_owner_scram, crm_app_scram, crm_backup_scram  (psql -v ...)
--            Verificadores SCRAM-SHA-256 gerados por database/scripts/scram_verifier.sh.
--            A senha em texto claro NUNCA entra numa instrução SQL: o servidor registra o
--            texto de comandos que falham (log_min_error_statement).
-- Reverter : ver database/rollback/README.md (o bootstrap não é migration).
-- Uso      : psql -v ON_ERROR_STOP=1 -v crm_owner_scram=... -v crm_app_scram=...
--                 -v crm_backup_scram=... -f 00_roles_and_schema.sql
-- Verificação (10_verify_roles.sql): rodar como o mesmo administrador do bootstrap.
-- =====================================================================
\set ON_ERROR_STOP on

-- 1. Variáveis obrigatórias ---------------------------------------------
SELECT :{?crm_owner_scram} AND :{?crm_app_scram} AND :{?crm_backup_scram} AS vars_ok \gset
\if :vars_ok
\else
  DO $$ BEGIN
    RAISE EXCEPTION 'bootstrap: defina crm_owner_scram, crm_app_scram e crm_backup_scram (psql -v)';
  END $$;
\endif

-- Só aceita verificadores SCRAM-SHA-256 (nunca senha em texto claro)
SELECT :'crm_owner_scram' ~ '^SCRAM-SHA-256\$[0-9]+:[A-Za-z0-9+/=]+\$[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+$'
   AND :'crm_app_scram' ~ '^SCRAM-SHA-256\$[0-9]+:[A-Za-z0-9+/=]+\$[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+$'
   AND :'crm_backup_scram' ~ '^SCRAM-SHA-256\$[0-9]+:[A-Za-z0-9+/=]+\$[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+$' AS fmt_ok \gset
\if :fmt_ok
\else
  DO $$ BEGIN
    RAISE EXCEPTION 'bootstrap: os valores devem ser verificadores SCRAM-SHA-256 (use database/scripts/scram_verifier.sh)';
  END $$;
\endif

-- 2. Papéis (atributos de privilégio só na criação; a verificação 10_ aborta se divergirem)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_definer') THEN
    CREATE ROLE crm_definer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_owner') THEN
    CREATE ROLE crm_owner LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_app') THEN
    CREATE ROLE crm_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_backup') THEN
    CREATE ROLE crm_backup LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION;
  END IF;
END $$;

-- Senhas como verificador SCRAM (rotação = reexecutar com novos verificadores).
ALTER ROLE crm_definer NOLOGIN;
ALTER ROLE crm_owner   LOGIN NOINHERIT PASSWORD :'crm_owner_scram';
ALTER ROLE crm_app     LOGIN NOINHERIT PASSWORD :'crm_app_scram';
ALTER ROLE crm_backup  LOGIN NOINHERIT PASSWORD :'crm_backup_scram';

-- 3. crm_owner pode assumir o papel crm_definer (necessário para ALTER FUNCTION ... OWNER TO, F04)
GRANT crm_definer TO crm_owner;

-- 4. Schema core ----------------------------------------------------------
-- O administrador precisa poder assumir crm_owner para criar o schema em seu nome.
-- A associação permanece (o administrador já é todo-poderoso); documentada no contrato.
-- PostgreSQL 16+: quem tem CREATEROLE (caso do postgres no Supabase) recebe, ao criar o papel, uma
-- associação SEM a opção SET; ser MEMBER não basta para SET ROLE, então o teste é pela opção SET.
DO $$
BEGIN
  IF current_setting('server_version_num')::int >= 160000 THEN
    IF NOT pg_has_role(current_user, 'crm_owner', 'SET') THEN
      EXECUTE format('GRANT crm_owner TO %I WITH SET TRUE', current_user);
    END IF;
  ELSIF NOT pg_has_role(current_user, 'crm_owner', 'MEMBER') THEN
    EXECUTE format('GRANT crm_owner TO %I', current_user);
  END IF;
END $$;

CREATE SCHEMA IF NOT EXISTS core AUTHORIZATION crm_owner;
ALTER SCHEMA core OWNER TO crm_owner;

-- Nada de CREATE em public para ninguém (já é o padrão no PostgreSQL 15+)
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- 5. Parâmetros de sessão por papel ---------------------------------------
ALTER ROLE crm_app    SET statement_timeout = '15s';
ALTER ROLE crm_app    SET idle_in_transaction_session_timeout = '30s';
ALTER ROLE crm_app    SET search_path = core, pg_catalog;
ALTER ROLE crm_backup SET statement_timeout = '300s';
ALTER ROLE crm_backup SET search_path = core, pg_catalog;

-- 6. Verificação de segurança (aborta se algo estiver privilegiado) --------
\ir 10_verify_roles.sql
