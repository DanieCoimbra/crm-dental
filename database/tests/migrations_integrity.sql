-- =====================================================================
-- tests/migrations_integrity.sql | F05-migration-pipeline
-- Testes 19 e 23 (lado SQL): o histórico REAL do Flyway (core.flyway_schema_history) existe, está íntegro e
-- é invisível ao crm_app. Os testes que dependem de rodar o Flyway (24: validate e checksum alterado) estão
-- em tests/flyway_checks.sh.
-- Executar : como administrador, depois do `flyway migrate`.
-- =====================================================================
\set ON_ERROR_STOP on

-- Histórico presente, no schema core, do crm_owner ---------------------------------------------
DO $$
DECLARE
  owner_name text;
BEGIN
  IF to_regclass('core.flyway_schema_history') IS NULL THEN
    RAISE EXCEPTION 'T23: core.flyway_schema_history nao existe (as migrations nao rodaram pelo Flyway?)';
  END IF;
  SELECT pg_get_userbyid(relowner) INTO owner_name FROM pg_class WHERE oid = 'core.flyway_schema_history'::regclass;
  IF owner_name <> 'crm_owner' THEN
    RAISE EXCEPTION 'T23: o dono do historico deve ser crm_owner, e e %', owner_name;
  END IF;
END $$;

-- Todas as versões aplicadas com sucesso, contíguas, com checksum, por crm_owner ------------------
DO $$
DECLARE
  total int;
  ok_count int;
  top int;
BEGIN
  SELECT count(*), count(*) FILTER (WHERE success AND type = 'SQL' AND checksum IS NOT NULL AND installed_by = 'crm_owner'),
         max(version::int)
    INTO total, ok_count, top
    FROM core.flyway_schema_history
   WHERE version IS NOT NULL;
  IF total < 6 THEN
    RAISE EXCEPTION 'T23: esperadas ao menos 6 migrations (V001-V006), ha %', total;
  END IF;
  IF ok_count <> total THEN
    RAISE EXCEPTION 'T23: % de % migrations sem sucesso, sem checksum ou aplicadas por outro usuario', total - ok_count, total;
  END IF;
  IF top <> total THEN
    RAISE EXCEPTION 'T23: versoes nao contiguas (maior V%, % registros)', top, total;
  END IF;
  IF EXISTS (SELECT 1 FROM core.flyway_schema_history WHERE version IS NULL) THEN
    RAISE EXCEPTION 'T23: ha migrations repetiveis ou baseline inesperados no historico';
  END IF;
  IF EXISTS (SELECT 1 FROM core.flyway_schema_history WHERE NOT success) THEN
    RAISE EXCEPTION 'T23: ha migration com falha registrada no historico';
  END IF;
END $$;

-- Teste 19: o historico e invisivel a quem nao e o crm_owner --------------------------------------
DO $$
DECLARE
  r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['crm_app', 'crm_backup', 'crm_definer', 'anon', 'authenticated', 'service_role'] LOOP
    IF has_table_privilege(r, 'core.flyway_schema_history', 'SELECT')
       OR has_table_privilege(r, 'core.flyway_schema_history', 'INSERT')
       OR has_table_privilege(r, 'core.flyway_schema_history', 'UPDATE')
       OR has_table_privilege(r, 'core.flyway_schema_history', 'DELETE') THEN
      RAISE EXCEPTION 'T19: % tem privilegio sobre core.flyway_schema_history', r;
    END IF;
  END LOOP;
  IF EXISTS (SELECT 1 FROM pg_class c, LATERAL aclexplode(c.relacl) a
              WHERE c.oid = 'core.flyway_schema_history'::regclass AND a.grantee <> c.relowner) THEN
    RAISE EXCEPTION 'T19: o historico tem GRANT para alguem alem do dono';
  END IF;
END $$;

-- ...e a tentativa REAL do crm_app falha ----------------------------------------------------------
SET SESSION AUTHORIZATION crm_app;
DO $$
BEGIN
  BEGIN
    PERFORM 1 FROM core.flyway_schema_history;
    RAISE EXCEPTION 'T19: crm_app conseguiu ler core.flyway_schema_history';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    DELETE FROM core.flyway_schema_history;
    RAISE EXCEPTION 'T19: crm_app conseguiu apagar o historico do Flyway';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
RESET SESSION AUTHORIZATION;

-- O historico nao e tabela de negocio: nao tem clinic_id nem RLS (nenhum dado de paciente) ----------
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = 'core' AND table_name = 'flyway_schema_history' AND column_name = 'clinic_id') THEN
    RAISE EXCEPTION 'o historico do Flyway nao deveria ter clinic_id';
  END IF;
END $$;
