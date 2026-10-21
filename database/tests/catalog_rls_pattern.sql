-- =====================================================================
-- tests/catalog_rls_pattern.sql | F02-tenant-and-rls-foundation
-- Teste de catálogo da receita de RLS: toda tabela de negócio de core precisa de
--   (1) coluna clinic_id NOT NULL com índice iniciando por ela (em clinics: a chave id),
--   (2) RLS ENABLE + FORCE,
--   (3) política de SELECT para crm_backup,
--   (4) nenhuma política com USING (true) para crm_app (ou PUBLIC),
--   (5) nenhum GRANT de DELETE/TRUNCATE a crm_app sem política correspondente.
-- Executar: como administrador, depois das migrations. Falha se houver violação.
-- =====================================================================
\set ON_ERROR_STOP on

-- Retorna uma linha por violação. Função temporária: não deixa nada no banco.
CREATE FUNCTION pg_temp.rls_pattern_violations() RETURNS SETOF text
  LANGUAGE sql STABLE AS $$
  WITH tabelas AS (
    SELECT c.oid, c.relname, c.relrowsecurity, c.relforcerowsecurity
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'core' AND c.relkind IN ('r', 'p')
  ),
  backup AS (SELECT oid FROM pg_roles WHERE rolname = 'crm_backup'),
  app    AS (SELECT oid FROM pg_roles WHERE rolname = 'crm_app')
  SELECT format('%s: sem coluna clinic_id NOT NULL', t.relname)
    FROM tabelas t
   WHERE t.relname <> 'clinics'
     AND NOT EXISTS (SELECT 1 FROM pg_attribute a
                      WHERE a.attrelid = t.oid AND a.attname = 'clinic_id'
                        AND a.attnotnull AND NOT a.attisdropped)
  UNION ALL
  SELECT format('%s: sem indice iniciando por clinic_id', t.relname)
    FROM tabelas t
   WHERE t.relname <> 'clinics'
     AND NOT EXISTS (SELECT 1 FROM pg_index i
                      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                     WHERE i.indrelid = t.oid AND a.attname = 'clinic_id')
  UNION ALL
  SELECT format('%s: RLS nao esta ENABLE + FORCE', t.relname)
    FROM tabelas t WHERE NOT (t.relrowsecurity AND t.relforcerowsecurity)
  UNION ALL
  SELECT format('%s: sem politica de SELECT para crm_backup', t.relname)
    FROM tabelas t
   WHERE NOT EXISTS (SELECT 1 FROM pg_policy p
                      WHERE p.polrelid = t.oid AND p.polcmd IN ('r', '*')
                        AND (SELECT oid FROM backup) = ANY (p.polroles))
  UNION ALL
  SELECT format('%s: politica %s usa USING (true) para crm_app/PUBLIC', t.relname, p.polname)
    FROM tabelas t JOIN pg_policy p ON p.polrelid = t.oid
   WHERE pg_get_expr(p.polqual, p.polrelid) = 'true'
     AND ((SELECT oid FROM app) = ANY (p.polroles) OR 0 = ANY (p.polroles))
  UNION ALL
  SELECT format('%s: crm_app tem DELETE/TRUNCATE sem politica de DELETE', t.relname)
    FROM tabelas t
   WHERE (has_table_privilege('crm_app', t.oid, 'TRUNCATE')
          OR (has_table_privilege('crm_app', t.oid, 'DELETE')
              AND NOT EXISTS (SELECT 1 FROM pg_policy p
                               WHERE p.polrelid = t.oid AND p.polcmd IN ('d', '*')
                                 AND (SELECT oid FROM app) = ANY (p.polroles))))
$$;

-- 1) O catálogo real não tem violações
DO $$
DECLARE v text;
BEGIN
  FOR v IN SELECT * FROM pg_temp.rls_pattern_violations() LOOP
    RAISE EXCEPTION 'receita de RLS violada: %', v;
  END LOOP;
END $$;

-- 2) Auto-teste: o verificador realmente detecta violações (tudo revertido)
BEGIN;
SET LOCAL ROLE crm_owner;

-- tabela conforme: nenhuma violação
CREATE TABLE core.t_ok_f02 (id uuid PRIMARY KEY, clinic_id uuid NOT NULL);
CREATE INDEX ix_t_ok_f02 ON core.t_ok_f02 (clinic_id);
ALTER TABLE core.t_ok_f02 ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.t_ok_f02 FORCE ROW LEVEL SECURITY;
CREATE POLICY ok_sel ON core.t_ok_f02 FOR SELECT TO crm_app USING (clinic_id = core.current_clinic_id());
CREATE POLICY ok_bk  ON core.t_ok_f02 FOR SELECT TO crm_backup USING (true);

-- tabela sem RLS, sem índice e sem política do backup
CREATE TABLE core.t_ruim_f02 (id uuid PRIMARY KEY, clinic_id uuid NOT NULL);

-- tabela com RLS mas USING (true) para crm_app e DELETE sem política
CREATE TABLE core.t_aberta_f02 (id uuid PRIMARY KEY, clinic_id uuid NOT NULL);
CREATE INDEX ix_t_aberta_f02 ON core.t_aberta_f02 (clinic_id);
ALTER TABLE core.t_aberta_f02 ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.t_aberta_f02 FORCE ROW LEVEL SECURITY;
CREATE POLICY aberta_sel ON core.t_aberta_f02 FOR SELECT TO crm_app USING (true);
CREATE POLICY aberta_bk  ON core.t_aberta_f02 FOR SELECT TO crm_backup USING (true);
GRANT SELECT, DELETE ON core.t_aberta_f02 TO crm_app;

-- tabela com RLS sem FORCE
CREATE TABLE core.t_semforce_f02 (id uuid PRIMARY KEY, clinic_id uuid NOT NULL);
CREATE INDEX ix_t_semforce_f02 ON core.t_semforce_f02 (clinic_id);
ALTER TABLE core.t_semforce_f02 ENABLE ROW LEVEL SECURITY;
CREATE POLICY sf_bk ON core.t_semforce_f02 FOR SELECT TO crm_backup USING (true);

-- tabela sem coluna clinic_id
CREATE TABLE core.t_semcoluna_f02 (id uuid PRIMARY KEY);
RESET ROLE;

DO $$
DECLARE
  v text; todas text;
BEGIN
  SELECT string_agg(x, E'\n') INTO todas FROM pg_temp.rls_pattern_violations() x;
  IF todas ILIKE '%t_ok_f02%' THEN
    RAISE EXCEPTION 'tabela conforme foi acusada indevidamente: %', todas;
  END IF;
  FOREACH v IN ARRAY ARRAY[
    't_ruim_f02: RLS nao esta ENABLE + FORCE',
    't_ruim_f02: sem politica de SELECT para crm_backup',
    't_ruim_f02: sem indice iniciando por clinic_id',
    't_aberta_f02: politica aberta_sel usa USING (true) para crm_app/PUBLIC',
    't_aberta_f02: crm_app tem DELETE/TRUNCATE sem politica de DELETE',
    't_semforce_f02: RLS nao esta ENABLE + FORCE',
    't_semcoluna_f02: sem coluna clinic_id NOT NULL'
  ] LOOP
    IF todas IS NULL OR position(v IN todas) = 0 THEN
      RAISE EXCEPTION 'o verificador deveria acusar: % (obtido: %)', v, todas;
    END IF;
  END LOOP;
END $$;
ROLLBACK;

SELECT 'catalog_rls_pattern: OK' AS resultado;
