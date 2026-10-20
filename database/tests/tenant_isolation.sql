-- =====================================================================
-- tests/tenant_isolation.sql | F03-core-tables
-- Testes 1, 2, 3, 6 e 22 (+ leitura do backup): isolamento entre as clínicas A e B nas três
-- tabelas, exercitado como o backend o faria (crm_app, tenant por transação).
-- Tudo dentro de uma transação revertida (ROLLBACK): nada persiste.
-- =====================================================================
\set ON_ERROR_STOP on

\set ca  'aaaaaaaa-0000-0000-0000-00000000000a'
\set cb  'bbbbbbbb-0000-0000-0000-00000000000b'
\set ua_d 'aaaaaaaa-1111-0000-0000-000000000001'
\set ua_r 'aaaaaaaa-1111-0000-0000-000000000002'
\set ub_d 'bbbbbbbb-1111-0000-0000-000000000001'
\set ub_r 'bbbbbbbb-1111-0000-0000-000000000002'

BEGIN;
SET SESSION AUTHORIZATION crm_app;

-- Fixtures pelo caminho da aplicação (fluxo de registro): tenant A
SELECT set_config('app.clinic_id', :'ca', true) AS _ \gset
INSERT INTO core.clinics (id, name) VALUES (:'ca', 'Clinica A');
INSERT INTO core.users (id, clinic_id, name, email, password_hash, role, created_by) VALUES
  (:'ua_d', :'ca', 'Dentista A', 'dentista.a@teste.local', 'hash-a', 'DENTIST_ADMIN', NULL),
  (:'ua_r', :'ca', 'Recep A',    'recep.a@teste.local',    'hash-a', 'RECEPTIONIST',  :'ua_d');
INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
  VALUES (:'ca', :'ua_d', gen_random_uuid(), repeat('a', 64), now() + interval '7 days');

-- Fixtures: tenant B
SELECT set_config('app.clinic_id', :'cb', true) AS _ \gset
INSERT INTO core.clinics (id, name) VALUES (:'cb', 'Clinica B');
INSERT INTO core.users (id, clinic_id, name, email, password_hash, role, created_by) VALUES
  (:'ub_d', :'cb', 'Dentista B', 'dentista.b@teste.local', 'hash-b', 'DENTIST_ADMIN', NULL),
  (:'ub_r', :'cb', 'Recep B',    'recep.b@teste.local',    'hash-b', 'RECEPTIONIST',  :'ub_d');
INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
  VALUES (:'cb', :'ub_d', gen_random_uuid(), repeat('b', 64), now() + interval '7 days');

-- T1: leitura isolada, nos dois sentidos --------------------------------
SELECT set_config('app.clinic_id', :'ca', true) AS _ \gset
DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM core.clinics;         IF n <> 1 THEN RAISE EXCEPTION 'T1(A): clinics=%', n; END IF;
  SELECT count(*) INTO n FROM core.users;           IF n <> 2 THEN RAISE EXCEPTION 'T1(A): users=%', n; END IF;
  SELECT count(*) INTO n FROM core.refresh_tokens;  IF n <> 1 THEN RAISE EXCEPTION 'T1(A): tokens=%', n; END IF;
  IF EXISTS (SELECT 1 FROM core.clinics WHERE id = 'bbbbbbbb-0000-0000-0000-00000000000b')
     OR EXISTS (SELECT 1 FROM core.users WHERE clinic_id = 'bbbbbbbb-0000-0000-0000-00000000000b')
     OR EXISTS (SELECT 1 FROM core.refresh_tokens WHERE clinic_id = 'bbbbbbbb-0000-0000-0000-00000000000b') THEN
    RAISE EXCEPTION 'T1: A enxergou dados de B';
  END IF;
END $$;

SELECT set_config('app.clinic_id', :'cb', true) AS _ \gset
DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM core.clinics;         IF n <> 1 THEN RAISE EXCEPTION 'T1(B): clinics=%', n; END IF;
  SELECT count(*) INTO n FROM core.users;           IF n <> 2 THEN RAISE EXCEPTION 'T1(B): users=%', n; END IF;
  SELECT count(*) INTO n FROM core.refresh_tokens;  IF n <> 1 THEN RAISE EXCEPTION 'T1(B): tokens=%', n; END IF;
  IF EXISTS (SELECT 1 FROM core.users WHERE clinic_id = 'aaaaaaaa-0000-0000-0000-00000000000a') THEN
    RAISE EXCEPTION 'T1: B enxergou dados de A';
  END IF;
END $$;

-- T2: alteração e remoção cruzadas (tenant A tentando mexer em B) -----------
SELECT set_config('app.clinic_id', :'ca', true) AS _ \gset
DO $$
DECLARE n bigint;
BEGIN
  UPDATE core.clinics SET name = 'invasao' WHERE id = 'bbbbbbbb-0000-0000-0000-00000000000b';
  GET DIAGNOSTICS n = ROW_COUNT; IF n <> 0 THEN RAISE EXCEPTION 'T2: A alterou clinic de B (%)', n; END IF;
  UPDATE core.users SET name = 'invasao' WHERE clinic_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
  GET DIAGNOSTICS n = ROW_COUNT; IF n <> 0 THEN RAISE EXCEPTION 'T2: A alterou users de B (%)', n; END IF;
  UPDATE core.refresh_tokens SET revoked_at = now() WHERE clinic_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
  GET DIAGNOSTICS n = ROW_COUNT; IF n <> 0 THEN RAISE EXCEPTION 'T2: A alterou tokens de B (%)', n; END IF;

  -- DELETE: sem privilégio (e sem política) em nenhuma das tabelas
  BEGIN DELETE FROM core.clinics;        RAISE EXCEPTION 'T2: DELETE em clinics deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN DELETE FROM core.users;          RAISE EXCEPTION 'T2: DELETE em users deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN DELETE FROM core.refresh_tokens; RAISE EXCEPTION 'T2: DELETE em refresh_tokens deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;

  -- as alterações legítimas no próprio tenant funcionam
  UPDATE core.clinics SET name = 'Clinica A v2' WHERE id = 'aaaaaaaa-0000-0000-0000-00000000000a';
  GET DIAGNOSTICS n = ROW_COUNT; IF n <> 1 THEN RAISE EXCEPTION 'T2: A nao conseguiu editar a propria clinica'; END IF;
  UPDATE core.users SET name = 'Recep A v2' WHERE id = 'aaaaaaaa-1111-0000-0000-000000000002';
  GET DIAGNOSTICS n = ROW_COUNT; IF n <> 1 THEN RAISE EXCEPTION 'T2: A nao conseguiu editar o proprio usuario'; END IF;
END $$;

-- T3 e T6: inserções em nome de outro tenant falham (WITH CHECK) -------------
DO $$
BEGIN
  BEGIN
    INSERT INTO core.users (clinic_id, name, email, password_hash, role)
      VALUES ('bbbbbbbb-0000-0000-0000-00000000000b', 'Intruso', 'intruso@teste.local', 'h', 'RECEPTIONIST');
    RAISE EXCEPTION 'T3: INSERT de usuario em clinica alheia deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;

  BEGIN
    INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
      VALUES ('bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-1111-0000-0000-000000000001',
              gen_random_uuid(), repeat('c', 64), now() + interval '1 day');
    RAISE EXCEPTION 'T3: INSERT de token em clinica alheia deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;

  -- T6: INSERT em clinics com id diferente do tenant
  BEGIN
    INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'Clinica fantasma');
    RAISE EXCEPTION 'T6: INSERT de clinica com id fora do tenant deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;

  -- mover linha própria para outro tenant (UPDATE do clinic_id) não é possível
  BEGIN
    UPDATE core.users SET clinic_id = 'bbbbbbbb-0000-0000-0000-00000000000b'
     WHERE id = 'aaaaaaaa-1111-0000-0000-000000000002';
    RAISE EXCEPTION 'T3: mover usuario para outro tenant deveria falhar';
  EXCEPTION WHEN insufficient_privilege OR check_violation THEN NULL; END;
END $$;

-- Sem tenant: nada visível, nada gravável ------------------------------------
SELECT set_config('app.clinic_id', '', true) AS _ \gset
DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM core.clinics;         IF n <> 0 THEN RAISE EXCEPTION 'sem tenant: clinics=%', n; END IF;
  SELECT count(*) INTO n FROM core.users;           IF n <> 0 THEN RAISE EXCEPTION 'sem tenant: users=%', n; END IF;
  SELECT count(*) INTO n FROM core.refresh_tokens;  IF n <> 0 THEN RAISE EXCEPTION 'sem tenant: tokens=%', n; END IF;
  BEGIN
    INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'Sem tenant');
    RAISE EXCEPTION 'sem tenant: INSERT deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE core.users SET name = 'x';
  GET DIAGNOSTICS n = ROW_COUNT; IF n <> 0 THEN RAISE EXCEPTION 'sem tenant: UPDATE afetou % linhas', n; END IF;
END $$;
RESET SESSION AUTHORIZATION;

-- Backup: lê as duas clínicas pelas políticas nomeadas e não escreve ----------
SET SESSION AUTHORIZATION crm_backup;
DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM core.clinics;         IF n <> 2 THEN RAISE EXCEPTION 'backup: clinics=%', n; END IF;
  SELECT count(*) INTO n FROM core.users;           IF n <> 4 THEN RAISE EXCEPTION 'backup: users=%', n; END IF;
  SELECT count(*) INTO n FROM core.refresh_tokens;  IF n <> 2 THEN RAISE EXCEPTION 'backup: tokens=%', n; END IF;
  BEGIN UPDATE core.users SET name = 'x';
        RAISE EXCEPTION 'backup: UPDATE deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'x');
        RAISE EXCEPTION 'backup: INSERT deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN DELETE FROM core.refresh_tokens;
        RAISE EXCEPTION 'backup: DELETE deveria falhar';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET SESSION AUTHORIZATION;

-- T22: o dono (crm_owner) também é filtrado pela RLS (FORCE) ------------------
SET SESSION AUTHORIZATION crm_owner;
SELECT set_config('app.clinic_id', :'ca', true) AS _ \gset
DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM core.users;           IF n <> 0 THEN RAISE EXCEPTION 'T22: crm_owner leu % usuarios', n; END IF;
  SELECT count(*) INTO n FROM core.clinics;         IF n <> 0 THEN RAISE EXCEPTION 'T22: crm_owner leu % clinicas', n; END IF;
  SELECT count(*) INTO n FROM core.refresh_tokens;  IF n <> 0 THEN RAISE EXCEPTION 'T22: crm_owner leu % tokens', n; END IF;
  BEGIN
    -- id novo (nao colide com nenhuma linha): so a RLS pode barrar este INSERT
    INSERT INTO core.clinics (id, name) VALUES ('aaaaaaaa-0000-0000-0000-0000000000ff', 'dono');
    RAISE EXCEPTION 'T22: crm_owner inseriu sem politica';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET SESSION AUTHORIZATION;

ROLLBACK;

-- Nada persistiu
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.clinics) THEN RAISE EXCEPTION 'fixtures nao foram revertidas'; END IF;
END $$;

SELECT 'tenant_isolation: OK' AS resultado;
