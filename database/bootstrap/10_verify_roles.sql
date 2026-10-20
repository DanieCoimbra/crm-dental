-- =====================================================================
-- F01-roles-and-bootstrap | 10_verify_roles.sql
-- Objetivo : abortar (RAISE EXCEPTION) se qualquer papel do CRM for privilegiado
--            ou se o schema core tiver dono errado.
-- Executar : incluído por 00_roles_and_schema.sql; também pode rodar sozinho
--            (somente leitura no catálogo), como o MESMO administrador do bootstrap:
--            a associação do administrador a crm_owner/papéis criados é a única tolerada.
-- Reverter : não se aplica (não altera nada).
-- =====================================================================
\set ON_ERROR_STOP on

DO $$
DECLARE
  r record;
  n integer;
BEGIN
  -- Os 4 papéis existem
  SELECT count(*) INTO n FROM pg_roles
   WHERE rolname IN ('crm_owner', 'crm_app', 'crm_backup', 'crm_definer');
  IF n <> 4 THEN
    RAISE EXCEPTION 'verificacao: esperados 4 papeis do CRM, encontrados %', n;
  END IF;

  -- Nenhum privilegiado
  FOR r IN
    SELECT rolname FROM pg_roles
     WHERE rolname IN ('crm_owner', 'crm_app', 'crm_backup', 'crm_definer')
       AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication)
  LOOP
    RAISE EXCEPTION 'verificacao: papel % tem atributo privilegiado (SUPERUSER/BYPASSRLS/CREATEROLE/CREATEDB/REPLICATION)', r.rolname;
  END LOOP;

  -- crm_definer nunca faz login; os demais fazem
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_definer' AND rolcanlogin) THEN
    RAISE EXCEPTION 'verificacao: crm_definer nao pode ter LOGIN';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles
              WHERE rolname IN ('crm_owner', 'crm_app', 'crm_backup') AND NOT rolcanlogin) THEN
    RAISE EXCEPTION 'verificacao: crm_owner, crm_app e crm_backup precisam de LOGIN';
  END IF;

  -- Única associação permitida dos 4 papéis: crm_owner -> crm_definer
  FOR r IN
    SELECT m.rolname AS membro, g.rolname AS grupo
      FROM pg_auth_members am
      JOIN pg_roles m ON m.oid = am.member
      JOIN pg_roles g ON g.oid = am.roleid
     WHERE m.rolname IN ('crm_owner', 'crm_app', 'crm_backup', 'crm_definer')
       AND NOT (m.rolname = 'crm_owner' AND g.rolname = 'crm_definer')
  LOOP
    RAISE EXCEPTION 'verificacao: % nao pode ser membro de % (anon, authenticated, service_role, supabase_admin, pg_* ou qualquer outro)', r.membro, r.grupo;
  END LOOP;

  -- Ninguém herda um dos 4 papéis por associação indevida (o crm_definer só tem crm_owner como membro)
  FOR r IN
    SELECT m.rolname AS membro, g.rolname AS grupo
      FROM pg_auth_members am
      JOIN pg_roles m ON m.oid = am.member
      JOIN pg_roles g ON g.oid = am.roleid
     WHERE g.rolname IN ('crm_app', 'crm_backup', 'crm_definer')
       AND m.rolname NOT IN ('crm_owner')
       AND m.rolname NOT IN (SELECT rolname FROM pg_roles WHERE rolsuper)
       AND m.rolname <> current_user
  LOOP
    RAISE EXCEPTION 'verificacao: % e membro de % (associacao inesperada)', r.membro, r.grupo;
  END LOOP;

  -- Schema core pertence a crm_owner
  IF NOT EXISTS (
    SELECT 1 FROM pg_namespace nsp JOIN pg_roles o ON o.oid = nsp.nspowner
     WHERE nsp.nspname = 'core' AND o.rolname = 'crm_owner'
  ) THEN
    RAISE EXCEPTION 'verificacao: schema core inexistente ou com dono diferente de crm_owner';
  END IF;
END $$;
