-- =====================================================================
-- tests/permissions_tables.sql | F03-core-tables
-- Teste 18 + matriz de privilégios + RLS/políticas das três tabelas.
-- Executar : como administrador, depois das migrations.
-- =====================================================================
\set ON_ERROR_STOP on

-- Matriz de privilégios de tabela: exatamente o contrato ------------------------------
DO $$
DECLARE
  t text;
  v text;
BEGIN
  FOREACH t IN ARRAY ARRAY['clinics', 'users', 'refresh_tokens'] LOOP
    SELECT string_agg(g, ', ' ORDER BY g) INTO v FROM (
      SELECT (CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE (SELECT rolname FROM pg_roles WHERE oid = a.grantee) END)
             || ':' || a.privilege_type AS g
        FROM pg_class c, LATERAL aclexplode(c.relacl) a
       WHERE c.oid = ('core.' || t)::regclass
         AND a.grantee <> c.relowner
    ) q;
    -- crm_app e crm_backup exatamente como o contrato; crm_definer só SELECT e DELETE (F04)
    IF v !~ '^crm_app:INSERT, crm_app:SELECT, crm_app:UPDATE, crm_backup:SELECT(, crm_definer:(DELETE|SELECT))*$' THEN
      RAISE EXCEPTION 'privilegios de core.% divergem do contrato: %', t, v;
    END IF;
    IF v ~ 'PUBLIC|anon|authenticated|service_role' THEN
      RAISE EXCEPTION 'core.% tem privilegio para PUBLIC/anon/authenticated/service_role: %', t, v;
    END IF;
    IF v ~ 'crm_definer:(INSERT|UPDATE|TRUNCATE|REFERENCES|TRIGGER)' THEN
      RAISE EXCEPTION 'crm_definer so pode ler (e apagar tokens vencidos): %', v;
    END IF;
    IF v ~ 'crm_app:(DELETE|TRUNCATE|REFERENCES|TRIGGER)' OR v ~ 'crm_backup:(INSERT|UPDATE|DELETE|TRUNCATE)' THEN
      RAISE EXCEPTION 'privilegio indevido em core.%: %', t, v;
    END IF;
    -- nenhum privilégio por coluna para crm_app / crm_backup
    IF EXISTS (SELECT 1 FROM pg_attribute at, LATERAL aclexplode(at.attacl) a
                WHERE at.attrelid = ('core.' || t)::regclass AND at.attacl IS NOT NULL
                  AND a.grantee IN (SELECT oid FROM pg_roles WHERE rolname IN ('crm_app', 'crm_backup'))) THEN
      RAISE EXCEPTION 'privilegio por coluna inesperado em core.%', t;
    END IF;
  END LOOP;
END $$;

-- RLS e políticas ---------------------------------------------------------------------
DO $$
DECLARE
  t text;
  v text;
BEGIN
  FOREACH t IN ARRAY ARRAY['clinics', 'users', 'refresh_tokens'] LOOP
    IF NOT (SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = ('core.' || t)::regclass) THEN
      RAISE EXCEPTION 'core.% sem RLS ENABLE + FORCE', t;
    END IF;
    -- políticas de crm_app: exatamente SELECT, INSERT, UPDATE (nenhuma de DELETE ou ALL)
    SELECT string_agg(CASE p.polcmd WHEN 'r' THEN 'SELECT' WHEN 'a' THEN 'INSERT' WHEN 'w' THEN 'UPDATE'
                                    WHEN 'd' THEN 'DELETE' ELSE 'ALL' END, ',' ORDER BY p.polcmd)
      INTO v
      FROM pg_policy p
     WHERE p.polrelid = ('core.' || t)::regclass
       AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles);
    -- polcmd ordena a(INSERT) < r(SELECT) < w(UPDATE)
    IF v IS DISTINCT FROM 'INSERT,SELECT,UPDATE' THEN
      RAISE EXCEPTION 'politicas de crm_app em core.% divergentes: %', t, v;
    END IF;
    -- política de backup: SELECT, USING (true)
    IF NOT EXISTS (SELECT 1 FROM pg_policy p
                    WHERE p.polrelid = ('core.' || t)::regclass AND p.polcmd = 'r'
                      AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_backup') = ANY (p.polroles)
                      AND pg_get_expr(p.polqual, p.polrelid) = 'true') THEN
      RAISE EXCEPTION 'core.% sem politica de SELECT para crm_backup', t;
    END IF;
    -- nenhuma política para PUBLIC ou para papéis fora do conjunto esperado
    IF EXISTS (SELECT 1 FROM pg_policy p, LATERAL unnest(p.polroles) r(oid)
                WHERE p.polrelid = ('core.' || t)::regclass
                  AND (r.oid = 0 OR r.oid NOT IN (SELECT oid FROM pg_roles
                                                   WHERE rolname IN ('crm_app', 'crm_backup', 'crm_definer')))) THEN
      RAISE EXCEPTION 'core.% tem politica para PUBLIC ou papel inesperado', t;
    END IF;
    -- políticas de crm_app nunca usam USING (true)
    IF EXISTS (SELECT 1 FROM pg_policy p
                WHERE p.polrelid = ('core.' || t)::regclass
                  AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles)
                  AND pg_get_expr(p.polqual, p.polrelid) = 'true') THEN
      RAISE EXCEPTION 'core.% tem politica aberta para crm_app', t;
    END IF;
  END LOOP;
END $$;

-- T18: crm_app não executa DDL nem operações destrutivas ---------------------------------
SET SESSION AUTHORIZATION crm_app;
DO $$
DECLARE
  t text;
  s text;
  pn text;
BEGIN
  FOREACH t IN ARRAY ARRAY['clinics', 'users', 'refresh_tokens'] LOOP
    SELECT polname INTO pn FROM pg_policy WHERE polrelid = ('core.' || t)::regclass ORDER BY polname LIMIT 1;
    IF pn IS NULL THEN RAISE EXCEPTION 'T18: core.% sem politicas para testar', t; END IF;
    FOREACH s IN ARRAY ARRAY[
      'DROP TABLE core.%1$s',
      'TRUNCATE core.%1$s',
      'ALTER TABLE core.%1$s ADD COLUMN extra integer',
      'ALTER TABLE core.%1$s DISABLE ROW LEVEL SECURITY',
      'ALTER TABLE core.%1$s NO FORCE ROW LEVEL SECURITY',
      'DROP POLICY %2$I ON core.%1$s',
      'ALTER POLICY %2$I ON core.%1$s USING (true)',
      'CREATE POLICY p_extra ON core.%1$s FOR SELECT TO crm_app USING (true)',
      'CREATE INDEX ix_extra ON core.%1$s (id)',
      'CREATE TRIGGER trg_extra BEFORE UPDATE ON core.%1$s FOR EACH ROW EXECUTE FUNCTION core.set_updated_at()',
      'DELETE FROM core.%1$s'
    ] LOOP
      BEGIN
        EXECUTE format(s, t, pn);
        RAISE EXCEPTION 'T18: crm_app executou: %', format(s, t, pn);
      EXCEPTION WHEN insufficient_privilege THEN NULL;
      END;
    END LOOP;
  END LOOP;
  BEGIN
    EXECUTE 'CREATE TABLE core.t_extra (id integer)';
    RAISE EXCEPTION 'T18: crm_app criou tabela';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;

  -- GRANT por quem não tem grant option só emite WARNING (nada é concedido): conferir o efeito
  FOREACH t IN ARRAY ARRAY['clinics', 'users', 'refresh_tokens'] LOOP
    EXECUTE format('GRANT ALL ON core.%I TO PUBLIC', t);
  END LOOP;
END $$;
RESET SESSION AUTHORIZATION;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_class c, LATERAL aclexplode(c.relacl) a
              WHERE c.oid IN ('core.clinics'::regclass, 'core.users'::regclass, 'core.refresh_tokens'::regclass)
                AND a.grantee = 0) THEN
    RAISE EXCEPTION 'T18: crm_app conseguiu conceder privilegios a PUBLIC';
  END IF;
  IF has_table_privilege('crm_app', 'core.users', 'DELETE') THEN
    RAISE EXCEPTION 'T18: crm_app ganhou DELETE em users';
  END IF;
END $$;

-- crm_backup e crm_definer: nada de escrita -----------------------------------------------
SET SESSION AUTHORIZATION crm_backup;
DO $$
BEGIN
  BEGIN EXECUTE 'DELETE FROM core.users'; RAISE EXCEPTION 'crm_backup apagou usuarios';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN EXECUTE 'TRUNCATE core.clinics'; RAISE EXCEPTION 'crm_backup truncou clinics';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET SESSION AUTHORIZATION;

SELECT 'permissions_tables: OK' AS resultado;
