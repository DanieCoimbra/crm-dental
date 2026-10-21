-- =====================================================================
-- tests/tenant_basics.sql | F02-tenant-and-rls-foundation
-- Testes: 4 e 5 (spec global do banco, seção 12) + função de tenant, privilégios das funções,
-- triggers genéricos e a receita de RLS aplicada a uma tabela-sonda.
-- Executar : como administrador do banco de teste, depois do bootstrap e das migrations.
-- A tabela-sonda existe só dentro de transações revertidas (ROLLBACK).
-- =====================================================================
\set ON_ERROR_STOP on

-- Valores de teste (dois tenants)
\set clinic_a '11111111-1111-1111-1111-111111111111'
\set clinic_b '22222222-2222-2222-2222-222222222222'

-- ---------------------------------------------------------------------
-- Privilégios das funções
-- ---------------------------------------------------------------------
DO $$
BEGIN
  IF NOT has_function_privilege('crm_app', 'core.current_clinic_id()', 'EXECUTE') THEN
    RAISE EXCEPTION 'crm_app deve executar core.current_clinic_id()';
  END IF;
  -- Fase 1 (V007): crm_definer também executa a função de tenant, para as políticas escopadas ao tenant
  IF has_function_privilege('crm_backup', 'core.current_clinic_id()', 'EXECUTE') THEN
    RAISE EXCEPTION 'crm_backup nao pode executar core.current_clinic_id()';
  END IF;
  IF NOT has_function_privilege('crm_definer', 'core.current_clinic_id()', 'EXECUTE') THEN
    RAISE EXCEPTION 'crm_definer deve executar core.current_clinic_id() (V007)';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace,
                    LATERAL aclexplode(p.proacl) a
              WHERE n.nspname = 'core' AND a.grantee = 0) THEN
    RAISE EXCEPTION 'nenhuma funcao de core pode ter EXECUTE para PUBLIC';
  END IF;
  IF has_function_privilege('crm_app', 'core.set_updated_at()', 'EXECUTE')
     OR has_function_privilege('crm_app', 'core.forbid_immutable_columns()', 'EXECUTE') THEN
    RAISE EXCEPTION 'funcoes de trigger nao devem ser executaveis por crm_app';
  END IF;
  -- STABLE, nunca IMMUTABLE; search_path fixo
  IF (SELECT provolatile FROM pg_proc WHERE oid = 'core.current_clinic_id()'::regprocedure) <> 's' THEN
    RAISE EXCEPTION 'core.current_clinic_id() deve ser STABLE';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid = 'core.current_clinic_id()'::regprocedure
                    AND 'search_path=pg_catalog' = ANY (p.proconfig)) THEN
    RAISE EXCEPTION 'core.current_clinic_id() deve fixar search_path';
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- Comportamento da função de tenant (como crm_app)
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_app;
BEGIN;
DO $$
BEGIN
  -- sem variável definida
  IF core.current_clinic_id() IS NOT NULL THEN
    RAISE EXCEPTION 'sem app.clinic_id a funcao deve devolver NULL';
  END IF;
END $$;

SELECT set_config('app.clinic_id', '', true) AS _ \gset
DO $$
BEGIN
  IF core.current_clinic_id() IS NOT NULL THEN
    RAISE EXCEPTION 'app.clinic_id vazio deve devolver NULL';
  END IF;
END $$;

SELECT set_config('app.clinic_id', :'clinic_a', true) AS _ \gset
DO $$
BEGIN
  IF core.current_clinic_id() IS DISTINCT FROM '11111111-1111-1111-1111-111111111111'::uuid THEN
    RAISE EXCEPTION 'a funcao deve devolver o UUID definido';
  END IF;
END $$;

-- valor que não é UUID: erro, nunca "todas as linhas"
SELECT set_config('app.clinic_id', 'nao-e-uuid', true) AS _ \gset
DO $$
BEGIN
  BEGIN
    PERFORM core.current_clinic_id();
    RAISE EXCEPTION 'valor nao UUID deveria gerar erro';
  EXCEPTION WHEN invalid_text_representation THEN NULL;
  END;
END $$;
ROLLBACK;
RESET SESSION AUTHORIZATION;

-- ---------------------------------------------------------------------
-- T5: set_config local expira no COMMIT e a conexão volta ao estado vazio
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_app;
BEGIN;
SELECT set_config('app.clinic_id', :'clinic_a', true) AS _ \gset
COMMIT;
DO $$
BEGIN
  IF NULLIF(current_setting('app.clinic_id', true), '') IS NOT NULL THEN
    RAISE EXCEPTION 'T5: app.clinic_id vazou apos COMMIT: %', current_setting('app.clinic_id', true);
  END IF;
  IF core.current_clinic_id() IS NOT NULL THEN
    RAISE EXCEPTION 'T5: tenant vazou entre transacoes';
  END IF;
END $$;
RESET SESSION AUTHORIZATION;

-- ---------------------------------------------------------------------
-- Receita aplicada a uma tabela-sonda (revertida ao final)
-- ---------------------------------------------------------------------
BEGIN;
SET LOCAL ROLE crm_owner;

CREATE TABLE core.t_probe_f02 (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id  uuid NOT NULL,
  note       text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_t_probe_f02_clinic ON core.t_probe_f02 (clinic_id);
ALTER TABLE core.t_probe_f02 ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.t_probe_f02 FORCE  ROW LEVEL SECURITY;
CREATE POLICY probe_sel ON core.t_probe_f02 FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY probe_ins ON core.t_probe_f02 FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id());
CREATE POLICY probe_upd ON core.t_probe_f02 FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id())
  WITH CHECK (clinic_id = core.current_clinic_id());
CREATE POLICY probe_backup_sel ON core.t_probe_f02 FOR SELECT TO crm_backup USING (true);
GRANT SELECT, INSERT, UPDATE ON core.t_probe_f02 TO crm_app;
GRANT SELECT ON core.t_probe_f02 TO crm_backup;
CREATE TRIGGER trg_set_updated_at BEFORE UPDATE ON core.t_probe_f02
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
CREATE TRIGGER trg_forbid_immutable BEFORE UPDATE ON core.t_probe_f02
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'clinic_id', 'created_at');
RESET ROLE;

SET SESSION AUTHORIZATION crm_app;

-- T4: sem app.clinic_id -> 0 linhas e INSERT falha
DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM core.t_probe_f02;
  IF n <> 0 THEN RAISE EXCEPTION 'T4: sem tenant a leitura deveria devolver 0 linhas (%)', n; END IF;
  BEGIN
    INSERT INTO core.t_probe_f02 (clinic_id, note) VALUES ('11111111-1111-1111-1111-111111111111', 'x');
    RAISE EXCEPTION 'T4: INSERT sem tenant deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL;  -- violação de RLS = 42501
  END;
END $$;

-- Tenant A insere; tenant B não enxerga nem altera
SELECT set_config('app.clinic_id', :'clinic_a', true) AS _ \gset
INSERT INTO core.t_probe_f02 (clinic_id, note, updated_at)
  VALUES (:'clinic_a', 'linha-a', '2000-01-01T00:00:00Z');
DO $$
BEGIN
  -- INSERT em nome de outro tenant falha
  BEGIN
    INSERT INTO core.t_probe_f02 (clinic_id, note) VALUES ('22222222-2222-2222-2222-222222222222', 'intruso');
    RAISE EXCEPTION 'INSERT com clinic_id de outro tenant deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;

-- Trigger de updated_at
UPDATE core.t_probe_f02 SET note = 'linha-a-v2' WHERE note = 'linha-a';
DO $$
BEGIN
  IF (SELECT updated_at FROM core.t_probe_f02 WHERE note = 'linha-a-v2') <= '2000-01-01T00:00:00Z' THEN
    RAISE EXCEPTION 'set_updated_at nao atualizou updated_at';
  END IF;
END $$;

-- Colunas imutáveis: listadas falham (23514), as demais passam
DO $$
BEGIN
  BEGIN
    UPDATE core.t_probe_f02 SET clinic_id = '22222222-2222-2222-2222-222222222222';
    RAISE EXCEPTION 'mudar clinic_id deveria falhar';
  EXCEPTION WHEN insufficient_privilege OR check_violation THEN NULL;
  END;
  BEGIN
    UPDATE core.t_probe_f02 SET created_at = now() + interval '1 day';
    RAISE EXCEPTION 'mudar created_at deveria falhar';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE core.t_probe_f02 SET id = gen_random_uuid();
    RAISE EXCEPTION 'mudar id deveria falhar';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  UPDATE core.t_probe_f02 SET note = 'ainda-permitido';
END $$;

-- Tenant B: não enxerga e não altera a linha de A
SELECT set_config('app.clinic_id', :'clinic_b', true) AS _ \gset
DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM core.t_probe_f02;
  IF n <> 0 THEN RAISE EXCEPTION 'tenant B enxergou % linha(s) de A', n; END IF;
  UPDATE core.t_probe_f02 SET note = 'invasao';
  GET DIAGNOSTICS n = ROW_COUNT;
  IF n <> 0 THEN RAISE EXCEPTION 'tenant B alterou % linha(s) de A', n; END IF;
END $$;

RESET SESSION AUTHORIZATION;

-- crm_backup lê tudo pela política nomeada, sem BYPASSRLS
SET SESSION AUTHORIZATION crm_backup;
DO $$
BEGIN
  IF (SELECT count(*) FROM core.t_probe_f02) <> 1 THEN
    RAISE EXCEPTION 'crm_backup deveria ler a linha da sonda pela politica de backup';
  END IF;
END $$;
RESET SESSION AUTHORIZATION;

-- Nome de coluna inexistente no trigger: erro de programação (42703)
SET LOCAL ROLE crm_owner;
CREATE TRIGGER trg_forbid_typo BEFORE UPDATE ON core.t_probe_f02
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('coluna_que_nao_existe');
RESET ROLE;
SET SESSION AUTHORIZATION crm_app;
SELECT set_config('app.clinic_id', :'clinic_a', true) AS _ \gset
DO $$
BEGIN
  BEGIN
    UPDATE core.t_probe_f02 SET note = 'x';
    RAISE EXCEPTION 'coluna inexistente no trigger deveria gerar erro';
  EXCEPTION WHEN undefined_column THEN NULL;
  END;
END $$;
RESET SESSION AUTHORIZATION;
ROLLBACK;

-- A sonda não persiste
DO $$
BEGIN
  IF to_regclass('core.t_probe_f02') IS NOT NULL THEN
    RAISE EXCEPTION 'tabela-sonda nao foi revertida';
  END IF;
END $$;

SELECT 'tenant_basics: OK' AS resultado;
