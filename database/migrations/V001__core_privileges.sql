-- =====================================================================
-- V001__core_privileges.sql | F01-roles-and-bootstrap
-- Objetivo : endurecer os privilégios do schema core (nada para PUBLIC,
--            anon, authenticated e service_role) e conceder apenas USAGE
--            aos papéis do CRM.
-- Executar : scripts/apply_migrations.sh, como crm_owner (dono do schema core).
-- Reverter : database/rollback/U001__core_privileges.sql
-- =====================================================================

-- Ninguém além do dono acessa o schema por padrão
REVOKE ALL ON SCHEMA core FROM PUBLIC;

-- Papéis internos do Supabase: sem acesso (ignora os que não existem, ex.: banco de testes)
DO $$
DECLARE
  r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['anon', 'authenticated', 'service_role'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('REVOKE ALL ON SCHEMA core FROM %I', r);
    END IF;
  END LOOP;
END $$;

-- Apenas USAGE; as permissões por tabela/função vêm das migrations de cada feature
GRANT USAGE ON SCHEMA core TO crm_app, crm_backup, crm_definer;

-- Objetos futuros criados por crm_owner nascem sem acesso para ninguém.
-- A função recebe EXECUTE para PUBLIC por padrão no PostgreSQL; um REVOKE com escopo de schema
-- não remove esse padrão global, por isso o ajuste vale para todos os schemas do crm_owner
-- (crm_owner só possui o schema core).
ALTER DEFAULT PRIVILEGES FOR ROLE crm_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE crm_owner IN SCHEMA core REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE crm_owner IN SCHEMA core REVOKE ALL ON SEQUENCES FROM PUBLIC;

-- Funções que porventura já existam no schema
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA core FROM PUBLIC;
