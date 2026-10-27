-- =====================================================================
-- tests/bootstrap_functions.sql | F04-bootstrap-functions
-- Testes 13, 14, 15, 16 + superfície de segurança do crm_definer e das funções SECURITY DEFINER.
-- As funções são chamadas como crm_app, SEM tenant definido (o caso de uso real).
-- Tudo dentro de uma transação revertida.
-- =====================================================================
\set ON_ERROR_STOP on

BEGIN;

-- Fixtures (como administrador; ignora a RLS)
INSERT INTO core.clinics (id, name) VALUES
  ('aaaaaaaa-0000-0000-0000-00000000000a', 'Clinica A'),
  ('bbbbbbbb-0000-0000-0000-00000000000b', 'Clinica B');
INSERT INTO core.users (id, clinic_id, name, email, password_hash, role, active) VALUES
  ('aaaaaaaa-1111-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-00000000000a', 'Dentista A', 'dentista.a@teste.local', 'hash-a', 'DENTIST_ADMIN', true),
  ('aaaaaaaa-1111-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-00000000000a', 'Recep A',    'recep.a@teste.local',    'hash-r', 'RECEPTIONIST',  false),
  ('bbbbbbbb-1111-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-00000000000b', 'Dentista B', 'dentista.b@teste.local', 'hash-b', 'DENTIST_ADMIN', true);
INSERT INTO core.refresh_tokens (id, clinic_id, user_id, family_id, token_hash, expires_at, used_at) VALUES
  ('f0000000-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-1111-0000-0000-000000000001',
   'ffffffff-0000-0000-0000-000000000001', repeat('a', 64), now() + interval '7 days', now()),
  ('f0000000-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-1111-0000-0000-000000000002',
   gen_random_uuid(), repeat('b', 64), now() - interval '31 days', NULL),
  ('f0000000-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-1111-0000-0000-000000000001',
   gen_random_uuid(), repeat('c', 64), now() - interval '29 days', NULL),
  ('f0000000-0000-0000-0000-000000000004', 'bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-1111-0000-0000-000000000001',
   gen_random_uuid(), repeat('d', 64), now() - interval '40 days', NULL);

SET SESSION AUTHORIZATION crm_app;
SELECT set_config('app.clinic_id', '', true) AS _ \gset

-- T13: login sem tenant definido --------------------------------------------
DO $$
DECLARE r record; n bigint;
BEGIN
  IF core.current_clinic_id() IS NOT NULL THEN RAISE EXCEPTION 'T13: o teste deve rodar sem tenant'; END IF;

  -- o acesso direto continua fechado para crm_app sem tenant
  SELECT count(*) INTO n FROM core.users;          IF n <> 0 THEN RAISE EXCEPTION 'T13: crm_app leu % usuarios sem tenant', n; END IF;
  SELECT count(*) INTO n FROM core.refresh_tokens; IF n <> 0 THEN RAISE EXCEPTION 'T13: crm_app leu % tokens sem tenant', n; END IF;

  -- qualquer caixa
  SELECT * INTO r FROM core.find_user_for_login('DENTISTA.A@Teste.Local');
  IF NOT FOUND THEN RAISE EXCEPTION 'T13: usuario nao encontrado com caixa diferente'; END IF;
  IF r.id <> 'aaaaaaaa-1111-0000-0000-000000000001' OR r.clinic_id <> 'aaaaaaaa-0000-0000-0000-00000000000a'
     OR r.role <> 'DENTIST_ADMIN' OR r.active IS NOT TRUE OR r.password_hash <> 'hash-a' THEN
    RAISE EXCEPTION 'T13: dados do usuario divergentes: %', r;
  END IF;

  -- usuário inativo é devolvido (a decisão é do backend), com active = false
  SELECT * INTO r FROM core.find_user_for_login('recep.a@teste.local');
  IF NOT FOUND OR r.active IS NOT FALSE OR r.role <> 'RECEPTIONIST' THEN
    RAISE EXCEPTION 'T13: usuario inativo deveria ser devolvido com active=false: %', r;
  END IF;

  -- inexistente, NULL (STRICT) e com espaços: 0 linhas
  SELECT count(*) INTO n FROM core.find_user_for_login('ninguem@teste.local');
  IF n <> 0 THEN RAISE EXCEPTION 'T13: e-mail inexistente devolveu % linhas', n; END IF;
  SELECT count(*) INTO n FROM core.find_user_for_login(NULL);
  IF n <> 0 THEN RAISE EXCEPTION 'T13: NULL devolveu % linhas', n; END IF;
  SELECT count(*) INTO n FROM core.find_user_for_login(' dentista.a@teste.local ');
  IF n <> 0 THEN RAISE EXCEPTION 'T13: e-mail com espacos nao deveria casar (backend faz trim)'; END IF;

  -- nunca devolve mais de uma linha
  SELECT count(*) INTO n FROM core.find_user_for_login('dentista.b@teste.local');
  IF n <> 1 THEN RAISE EXCEPTION 'T13: esperada 1 linha, obtidas %', n; END IF;
END $$;

-- T14: refresh por hash sem tenant ----------------------------------------------
DO $$
DECLARE r record; n bigint;
BEGIN
  SELECT * INTO r FROM core.find_refresh_token(repeat('a', 64));
  IF NOT FOUND THEN RAISE EXCEPTION 'T14: token nao encontrado pelo hash'; END IF;
  IF r.id <> 'f0000000-0000-0000-0000-000000000001' OR r.clinic_id <> 'aaaaaaaa-0000-0000-0000-00000000000a'
     OR r.user_id <> 'aaaaaaaa-1111-0000-0000-000000000001' OR r.family_id <> 'ffffffff-0000-0000-0000-000000000001'
     OR r.used_at IS NULL OR r.revoked_at IS NOT NULL OR r.expires_at <= now() THEN
    RAISE EXCEPTION 'T14: dados do token divergentes: %', r;
  END IF;
  SELECT count(*) INTO n FROM core.find_refresh_token(repeat('9', 64));
  IF n <> 0 THEN RAISE EXCEPTION 'T14: hash inexistente devolveu % linhas', n; END IF;
  SELECT count(*) INTO n FROM core.find_refresh_token(NULL);
  IF n <> 0 THEN RAISE EXCEPTION 'T14: NULL devolveu % linhas', n; END IF;
  SELECT count(*) INTO n FROM core.find_refresh_token(repeat('A', 64));
  IF n <> 0 THEN RAISE EXCEPTION 'T14: hash em maiusculas nao deveria casar'; END IF;
END $$;

-- Retorno mínimo: as colunas devolvidas são exatamente as do contrato
DO $$
BEGIN
  IF (SELECT proargnames FROM pg_proc WHERE oid = 'core.find_user_for_login(varchar)'::regprocedure)
     IS DISTINCT FROM ARRAY['p_email', 'id', 'clinic_id', 'password_hash', 'role', 'active'] THEN
    RAISE EXCEPTION 'find_user_for_login deve devolver apenas id, clinic_id, password_hash, role, active';
  END IF;
  IF (SELECT proargnames FROM pg_proc WHERE oid = 'core.find_refresh_token(varchar)'::regprocedure)
     IS DISTINCT FROM ARRAY['p_hash', 'id', 'clinic_id', 'user_id', 'family_id', 'expires_at', 'used_at', 'revoked_at'] THEN
    RAISE EXCEPTION 'find_refresh_token deve devolver apenas as colunas do contrato';
  END IF;
END $$;

-- search_path manipulado pelo chamador não afeta as funções ----------------------------
CREATE TEMP TABLE users (id uuid, clinic_id uuid, email varchar, password_hash varchar, role varchar, active boolean);
INSERT INTO users VALUES (gen_random_uuid(), gen_random_uuid(), 'falso@teste.local', 'hash-falso', 'DENTIST_ADMIN', true);
SET LOCAL search_path = pg_temp, core, public;
DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM core.find_user_for_login('falso@teste.local');
  IF n <> 0 THEN RAISE EXCEPTION 'search_path: funcao leu a tabela temporaria falsa'; END IF;
  SELECT count(*) INTO n FROM core.find_user_for_login('dentista.a@teste.local');
  IF n <> 1 THEN RAISE EXCEPTION 'search_path: funcao deixou de ler core.users'; END IF;
END $$;
RESET search_path;

-- T15: limpeza apaga só tokens vencidos há mais de 30 dias ---------------------------------
DO $$
DECLARE n integer;
BEGIN
  n := core.purge_expired_refresh_tokens();
  IF n <> 2 THEN RAISE EXCEPTION 'T15: esperados 2 tokens apagados (31 e 40 dias), obtidos %', n; END IF;
  n := core.purge_expired_refresh_tokens();
  IF n <> 0 THEN RAISE EXCEPTION 'T15: segunda execucao deveria apagar 0, obteve %', n; END IF;
  -- o acesso direto de crm_app a DELETE continua negado
  BEGIN
    DELETE FROM core.refresh_tokens;
    RAISE EXCEPTION 'T15: crm_app nao deveria ter DELETE';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET SESSION AUTHORIZATION;

DO $$
DECLARE ids uuid[];
BEGIN
  SELECT array_agg(id ORDER BY id) INTO ids FROM core.refresh_tokens;
  IF ids IS DISTINCT FROM ARRAY['f0000000-0000-0000-0000-000000000001'::uuid, 'f0000000-0000-0000-0000-000000000003'::uuid] THEN
    RAISE EXCEPTION 'T15: restaram os tokens errados: %', ids;
  END IF;
END $$;

-- T16 e superfície de segurança ---------------------------------------------------------
DO $$
DECLARE
  f record;
  names text[];
BEGIN
  -- as únicas funções SECURITY DEFINER de core são as três do contrato da Fase 0 mais as duas da Fase 1
  -- (write_audit e anonymize_patient; verificadas em tests/phase1_privileges.sql) e as duas da Fase 4 (write_document_audit e
  -- record_document_download; verificadas em tests/phase4_permissions.sql)
  SELECT array_agg(p.proname ORDER BY p.proname) INTO names
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'core' AND p.prosecdef
     AND p.proname NOT IN ('write_audit', 'anonymize_patient', 'write_document_audit', 'record_document_download');
  IF names IS DISTINCT FROM ARRAY['find_refresh_token', 'find_user_for_login', 'purge_expired_refresh_tokens'] THEN
    RAISE EXCEPTION 'T16: funcoes SECURITY DEFINER inesperadas em core: %', names;
  END IF;

  FOR f IN
    SELECT p.oid, p.proname, p.proowner, p.proconfig, p.proacl
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'core' AND p.prosecdef
       AND p.proname IN ('find_refresh_token', 'find_user_for_login', 'purge_expired_refresh_tokens')
  LOOP
    IF f.proowner <> (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') THEN
      RAISE EXCEPTION 'T16: % deve pertencer a crm_definer', f.proname;
    END IF;
    IF f.proconfig IS NULL OR NOT ('search_path=pg_catalog, pg_temp' = ANY (f.proconfig)) THEN
      RAISE EXCEPTION 'T16: % sem search_path fixo (pg_catalog, pg_temp): %', f.proname, f.proconfig;
    END IF;
    IF EXISTS (SELECT 1 FROM aclexplode(f.proacl) a WHERE a.grantee = 0) THEN
      RAISE EXCEPTION 'T16: % executavel por PUBLIC', f.proname;
    END IF;
    IF EXISTS (SELECT 1 FROM aclexplode(f.proacl) a
                WHERE a.grantee NOT IN (SELECT oid FROM pg_roles WHERE rolname IN ('crm_definer', 'crm_app'))) THEN
      RAISE EXCEPTION 'T16: % com EXECUTE para papel alem de crm_app', f.proname;
    END IF;
    IF NOT has_function_privilege('crm_app', f.oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'T16: crm_app deve executar %', f.proname;
    END IF;
    IF has_function_privilege('crm_backup', f.oid, 'EXECUTE') OR has_function_privilege('crm_owner', f.oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'T16: % executavel por crm_backup/crm_owner', f.proname;
    END IF;
    IF f.proname <> 'purge_expired_refresh_tokens'
       AND (SELECT provolatile FROM pg_proc WHERE oid = f.oid) <> 's' THEN
      RAISE EXCEPTION 'T16: % deve ser STABLE', f.proname;
    END IF;
  END LOOP;

  -- crm_definer: nunca cria objetos em core (a concessão temporária da V006 foi revogada)
  IF has_schema_privilege('crm_definer', 'core', 'CREATE') THEN
    RAISE EXCEPTION 'T16: crm_definer manteve CREATE em core';
  END IF;
  IF (SELECT rolcanlogin FROM pg_roles WHERE rolname = 'crm_definer') THEN
    RAISE EXCEPTION 'T16: crm_definer nao pode ter LOGIN';
  END IF;

  -- crm_definer em users: só as 6 colunas do login; nada em escrita
  IF has_table_privilege('crm_definer', 'core.users', 'SELECT')
     OR has_table_privilege('crm_definer', 'core.users', 'INSERT')
     OR has_table_privilege('crm_definer', 'core.users', 'UPDATE')
     OR has_table_privilege('crm_definer', 'core.users', 'DELETE') THEN
    RAISE EXCEPTION 'T16: crm_definer com privilegio de tabela em users';
  END IF;
  IF NOT (has_column_privilege('crm_definer', 'core.users', 'id', 'SELECT')
          AND has_column_privilege('crm_definer', 'core.users', 'clinic_id', 'SELECT')
          AND has_column_privilege('crm_definer', 'core.users', 'email', 'SELECT')
          AND has_column_privilege('crm_definer', 'core.users', 'password_hash', 'SELECT')
          AND has_column_privilege('crm_definer', 'core.users', 'role', 'SELECT')
          AND has_column_privilege('crm_definer', 'core.users', 'active', 'SELECT')) THEN
    RAISE EXCEPTION 'T16: crm_definer sem as colunas de login em users';
  END IF;
  IF has_column_privilege('crm_definer', 'core.users', 'name', 'SELECT')
     OR has_column_privilege('crm_definer', 'core.users', 'created_by', 'SELECT')
     OR has_column_privilege('crm_definer', 'core.users', 'version', 'SELECT')
     OR has_column_privilege('crm_definer', 'core.users', 'created_at', 'SELECT')
     OR has_column_privilege('crm_definer', 'core.users', 'updated_at', 'SELECT') THEN
    RAISE EXCEPTION 'T16: crm_definer le colunas alem das necessarias em users';
  END IF;
  IF has_column_privilege('crm_definer', 'core.users', 'password_hash', 'UPDATE') THEN
    RAISE EXCEPTION 'T16: crm_definer nao pode escrever em users';
  END IF;

  -- refresh_tokens: SELECT e DELETE; clinics: nada
  IF NOT (has_table_privilege('crm_definer', 'core.refresh_tokens', 'SELECT')
          AND has_table_privilege('crm_definer', 'core.refresh_tokens', 'DELETE')) THEN
    RAISE EXCEPTION 'T16: crm_definer sem SELECT/DELETE em refresh_tokens';
  END IF;
  IF has_table_privilege('crm_definer', 'core.refresh_tokens', 'INSERT')
     OR has_table_privilege('crm_definer', 'core.refresh_tokens', 'UPDATE')
     OR has_table_privilege('crm_definer', 'core.refresh_tokens', 'TRUNCATE') THEN
    RAISE EXCEPTION 'T16: crm_definer com escrita indevida em refresh_tokens';
  END IF;
  IF has_table_privilege('crm_definer', 'core.clinics', 'SELECT')
     OR has_column_privilege('crm_definer', 'core.clinics', 'name', 'SELECT') THEN
    RAISE EXCEPTION 'T16: crm_definer nao deve acessar clinics';
  END IF;

  -- políticas do crm_definer na Fase 0: exatamente as 3 do contrato (users e refresh_tokens); as da
  -- Fase 1 (escopadas ao tenant) são verificadas em tests/phase1_privileges.sql
  SELECT array_agg(c.relname || ':' || p.polname || ':' || p.polcmd::text ORDER BY c.relname, p.polname) INTO names
    FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
   WHERE (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') = ANY (p.polroles)
     AND c.relname IN ('users', 'refresh_tokens', 'clinics');
  IF names IS DISTINCT FROM ARRAY['refresh_tokens:refresh_definer_del:d', 'refresh_tokens:refresh_definer_sel:r', 'users:users_definer_sel:r'] THEN
    RAISE EXCEPTION 'T16: politicas do crm_definer divergentes: %', names;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policy p
                  WHERE p.polname = 'refresh_definer_del'
                    AND pg_get_expr(p.polqual, p.polrelid) LIKE '%30 days%') THEN
    RAISE EXCEPTION 'T16: a politica de DELETE do crm_definer deve limitar a tokens vencidos ha 30 dias';
  END IF;
END $$;

ROLLBACK;

SELECT 'bootstrap_functions: OK' AS resultado;
