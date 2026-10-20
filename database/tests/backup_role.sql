-- =====================================================================
-- tests/backup_role.sql | F06-backup-and-restore
-- Teste 27 (crm_backup é somente leitura) e as propriedades do papel de backup. O teste 26 (pg_dump real, criptografia)
-- e o 28 (restauração) estão em tests/backup_restore.sh.
-- Executar : como administrador, depois das migrations. Dados de teste ficam em transação e são desfeitos.
-- =====================================================================
\set ON_ERROR_STOP on

-- Propriedades do papel ----------------------------------------------------------------------------------
DO $$
DECLARE
  r record;
BEGIN
  SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolreplication, rolcanlogin, rolconfig INTO r
    FROM pg_roles WHERE rolname = 'crm_backup';
  IF NOT FOUND THEN RAISE EXCEPTION 'crm_backup nao existe'; END IF;
  IF r.rolsuper OR r.rolbypassrls OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication THEN
    RAISE EXCEPTION 'crm_backup tem atributo privilegiado: %', r;
  END IF;
  IF NOT r.rolcanlogin THEN RAISE EXCEPTION 'crm_backup precisa de LOGIN'; END IF;
  IF NOT (r.rolconfig @> ARRAY['statement_timeout=300s']) THEN
    RAISE EXCEPTION 'crm_backup sem statement_timeout=300s: %', r.rolconfig;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid
              WHERE m.member = (SELECT oid FROM pg_roles WHERE rolname = 'crm_backup')) THEN
    RAISE EXCEPTION 'crm_backup nao deve ser membro de nenhum papel';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = 'core' AND pg_get_userbyid(c.relowner) = 'crm_backup') THEN
    RAISE EXCEPTION 'crm_backup nao pode ser dono de objetos';
  END IF;
END $$;

-- Privilégios: SELECT em tudo o que é negócio e NADA além; USAGE no schema e CREATE não ----------------------------
DO $$
DECLARE
  t text;
BEGIN
  IF NOT has_schema_privilege('crm_backup', 'core', 'USAGE') THEN RAISE EXCEPTION 'crm_backup sem USAGE em core'; END IF;
  IF has_schema_privilege('crm_backup', 'core', 'CREATE') OR has_schema_privilege('crm_backup', 'public', 'CREATE') THEN
    RAISE EXCEPTION 'crm_backup consegue criar objetos em core ou public';
  END IF;
  FOR t IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'core' AND c.relkind IN ('r', 'p') AND c.relname <> 'flyway_schema_history' LOOP
    IF NOT has_table_privilege('crm_backup', 'core.' || t, 'SELECT') THEN
      RAISE EXCEPTION 'crm_backup sem SELECT em core.%', t;
    END IF;
    IF has_table_privilege('crm_backup', 'core.' || t, 'INSERT') OR has_table_privilege('crm_backup', 'core.' || t, 'UPDATE')
       OR has_table_privilege('crm_backup', 'core.' || t, 'DELETE') OR has_table_privilege('crm_backup', 'core.' || t, 'TRUNCATE')
       OR has_table_privilege('crm_backup', 'core.' || t, 'REFERENCES') OR has_table_privilege('crm_backup', 'core.' || t, 'TRIGGER') THEN
      RAISE EXCEPTION 'crm_backup tem privilegio de escrita em core.%', t;
    END IF;
  END LOOP;
  -- sem EXECUTE nas funções de aplicação (login, refresh, limpeza, tenant)
  IF has_function_privilege('crm_backup', 'core.find_user_for_login(varchar)', 'EXECUTE')
     OR has_function_privilege('crm_backup', 'core.find_refresh_token(varchar)', 'EXECUTE')
     OR has_function_privilege('crm_backup', 'core.purge_expired_refresh_tokens()', 'EXECUTE') THEN
    RAISE EXCEPTION 'crm_backup consegue executar funcoes de aplicacao';
  END IF;
END $$;

-- Dados de apoio (revertidos no ROLLBACK final): duas clínicas -----------------------------------------------------
BEGIN;
INSERT INTO core.clinics (id, name) VALUES
  ('0000000f-0000-4000-8000-00000000000a', 'Backup Clinica A'), ('0000000f-0000-4000-8000-00000000000b', 'Backup Clinica B');
INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES
  ('0000000f-0000-4000-8000-0000000000a1', '0000000f-0000-4000-8000-00000000000a', 'Usuario A', 'a@backup-role.test', 'x', 'RECEPTIONIST'),
  ('0000000f-0000-4000-8000-0000000000b1', '0000000f-0000-4000-8000-00000000000b', 'Usuario B', 'b@backup-role.test', 'x', 'RECEPTIONIST');

SET SESSION AUTHORIZATION crm_backup;

-- Lê TODAS as clínicas, sem app.clinic_id e sem BYPASSRLS (política nomeada USING (true)) ---------------------------
DO $$
BEGIN
  IF (SELECT count(*) FROM core.clinics WHERE id IN ('0000000f-0000-4000-8000-00000000000a', '0000000f-0000-4000-8000-00000000000b')) <> 2 THEN
    RAISE EXCEPTION 'T26: crm_backup nao enxerga as duas clinicas';
  END IF;
  IF (SELECT count(*) FROM core.users WHERE email LIKE '%@backup-role.test') <> 2 THEN
    RAISE EXCEPTION 'T26: crm_backup nao enxerga os usuarios das duas clinicas';
  END IF;
END $$;

-- Teste 27: nenhuma escrita, nenhum DDL ---------------------------------------------------------------------------------
DO $$
DECLARE
  stmt text;
BEGIN
  FOREACH stmt IN ARRAY ARRAY[
    $s$INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'Intrusa')$s$,
    $s$UPDATE core.clinics SET name = 'Alterada'$s$,
    $s$DELETE FROM core.clinics$s$,
    $s$TRUNCATE core.clinics CASCADE$s$,
    $s$INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (gen_random_uuid(), '0000000f-0000-4000-8000-00000000000a', 'X', 'x@x.test', 'x', 'RECEPTIONIST')$s$,
    $s$UPDATE core.users SET active = false$s$,
    $s$DELETE FROM core.users$s$,
    $s$UPDATE core.refresh_tokens SET revoked_at = now()$s$,
    $s$DELETE FROM core.refresh_tokens$s$,
    $s$CREATE TABLE core.t_intrusa (id int)$s$,
    $s$CREATE TABLE public.t_intrusa (id int)$s$,
    $s$DROP TABLE core.users$s$,
    $s$ALTER TABLE core.users ADD COLUMN extra int$s$,
    $s$CREATE INDEX ix_intrusa ON core.users (name)$s$,
    $s$CREATE FUNCTION core.f_intrusa() RETURNS int LANGUAGE sql AS 'SELECT 1'$s$,
    $s$CREATE SCHEMA intrusa$s$,
    $s$CREATE POLICY p_intrusa ON core.users FOR SELECT USING (true)$s$,
    $s$ALTER TABLE core.users DISABLE ROW LEVEL SECURITY$s$,
    $s$SELECT core.find_user_for_login('a@backup-role.test')$s$,
    $s$SELECT core.purge_expired_refresh_tokens()$s$,
    $s$SELECT 1 FROM core.flyway_schema_history$s$,
    $s$SET ROLE crm_owner$s$, $s$SET ROLE crm_app$s$, $s$SET ROLE crm_definer$s$, $s$SET ROLE postgres$s$
  ] LOOP
    BEGIN
      EXECUTE stmt;
      RAISE EXCEPTION 'T27: crm_backup conseguiu executar: %', stmt;
    EXCEPTION
      WHEN insufficient_privilege THEN NULL;  -- permission denied / must be owner
      WHEN invalid_schema_name THEN NULL;     -- sem CREATE no schema
    END;
  END LOOP;
END $$;
RESET SESSION AUTHORIZATION;

-- Nada mudou: as duas clínicas continuam intactas -------------------------------------------------------------------------
-- (um GRANT de quem não é dono só emite aviso "no privileges were granted"; conferimos que nada virou público)
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_class c, LATERAL aclexplode(c.relacl) a
              WHERE c.oid = 'core.users'::regclass AND a.grantee = 0) THEN
    RAISE EXCEPTION 'T27: core.users ganhou privilegio para PUBLIC';
  END IF;
  IF (SELECT count(*) FROM core.clinics WHERE name IN ('Backup Clinica A', 'Backup Clinica B')) <> 2
     OR EXISTS (SELECT 1 FROM core.clinics WHERE name IN ('Intrusa', 'Alterada'))
     OR to_regclass('core.t_intrusa') IS NOT NULL OR to_regclass('public.t_intrusa') IS NOT NULL THEN
    RAISE EXCEPTION 'T27: o estado do banco mudou durante as tentativas do crm_backup';
  END IF;
END $$;
ROLLBACK;

-- Cada tabela de negócio tem a política nomeada de leitura do crm_backup, e nenhuma é permissiva demais --------------------
DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT c.oid, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'core' AND c.relkind IN ('r', 'p') AND c.relrowsecurity AND c.relname <> 'flyway_schema_history' LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_policy p
                    WHERE p.polrelid = t.oid AND p.polname LIKE '%\_backup\_sel' AND p.polcmd = 'r'
                      AND p.polroles = ARRAY[(SELECT oid FROM pg_roles WHERE rolname = 'crm_backup')]
                      AND pg_get_expr(p.polqual, p.polrelid) = 'true') THEN
      RAISE EXCEPTION 'core.% sem politica <prefixo>_backup_sel (SELECT TO crm_backup USING (true))', t.relname;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_policy p
                WHERE p.polrelid = t.oid AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_backup') = ANY (p.polroles)
                  AND p.polcmd <> 'r') THEN
      RAISE EXCEPTION 'core.% tem politica de escrita para o crm_backup', t.relname;
    END IF;
  END LOOP;
END $$;
