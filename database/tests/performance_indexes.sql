-- =====================================================================
-- tests/performance_indexes.sql | F03-core-tables
-- Meta 4 do PRD: login por e-mail, refresh por hash e listagem de usuários usam índice.
-- Carga sintética: 100 clínicas x 1.000 usuários (100 mil) e 100 mil refresh tokens.
-- Tudo dentro de uma transação revertida.
-- =====================================================================
\set ON_ERROR_STOP on

BEGIN;

-- Carga (como administrador; ignora a RLS)
INSERT INTO core.clinics (id, name)
SELECT ('00000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, 'Clinica perf ' || g
  FROM generate_series(1, 100) g;

INSERT INTO core.users (id, clinic_id, name, email, password_hash, role)
SELECT gen_random_uuid(),
       ('00000000-0000-0000-0000-' || lpad(((g % 100) + 1)::text, 12, '0'))::uuid,
       'Usuario ' || lpad(g::text, 6, '0'),
       'u' || g || '@perf.local',
       'hash',
       'RECEPTIONIST'
  FROM generate_series(1, 100000) g;

INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
SELECT u.clinic_id, u.id, gen_random_uuid(),
       encode(sha256(convert_to(u.email, 'UTF8')), 'hex'), now() + interval '7 days'
  FROM core.users u;

ANALYZE core.users;
ANALYZE core.refresh_tokens;

CREATE FUNCTION pg_temp.plan_json(p_sql text) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE r json;
BEGIN
  EXECUTE 'EXPLAIN (ANALYZE, FORMAT JSON) ' || p_sql INTO r;
  RETURN r::jsonb;
END $$;

DO $$
DECLARE
  p jsonb;
  ms numeric;
BEGIN
  -- Login: busca por e-mail (F04 usa exatamente este predicado)
  p := pg_temp.plan_json($q$SELECT id, clinic_id, password_hash, role, active FROM core.users WHERE email = 'u50000@perf.local'$q$);
  IF p::text NOT LIKE '%"Index Name": "ux_users_email"%' OR p::text LIKE '%Seq Scan%' THEN
    RAISE EXCEPTION 'login por e-mail nao usa ux_users_email: %', p;
  END IF;
  ms := (p -> 0 ->> 'Execution Time')::numeric;
  RAISE NOTICE 'login por email: % ms', ms;
  IF ms > 50 THEN RAISE EXCEPTION 'login por e-mail lento demais: % ms', ms; END IF;

  -- Refresh: busca por hash
  p := pg_temp.plan_json(format($q$SELECT id, clinic_id, user_id, family_id, expires_at, used_at, revoked_at
                                    FROM core.refresh_tokens WHERE token_hash = %L$q$,
                                 encode(sha256(convert_to('u50000@perf.local', 'UTF8')), 'hex')));
  IF p::text NOT LIKE '%"Index Name": "ux_refresh_hash"%' OR p::text LIKE '%Seq Scan%' THEN
    RAISE EXCEPTION 'refresh por hash nao usa ux_refresh_hash: %', p;
  END IF;
  ms := (p -> 0 ->> 'Execution Time')::numeric;
  RAISE NOTICE 'refresh por hash: % ms', ms;
  IF ms > 50 THEN RAISE EXCEPTION 'refresh por hash lento demais: % ms', ms; END IF;

  -- Revogar a cadeia (family_id) e as sessões do usuário (user_id)
  p := pg_temp.plan_json($q$UPDATE core.refresh_tokens SET revoked_at = now()
                             WHERE family_id = (SELECT family_id FROM core.refresh_tokens LIMIT 1)$q$);
  IF p::text LIKE '%Seq Scan on refresh_tokens%' AND p::text NOT LIKE '%ix_refresh_family%' THEN
    RAISE NOTICE 'plano da revogacao por family_id: %', p;
  END IF;
END $$;

-- As funções de login/refresh/limpeza (F04), chamadas 1.000 vezes: média <= 5 ms (meta do PRD)
DO $$
DECLARE
  t0 timestamptz;
  i integer;
  avg_login numeric;
  avg_refresh numeric;
  purged integer;
  ms_purge numeric;
BEGIN
  t0 := clock_timestamp();
  FOR i IN 1..1000 LOOP
    PERFORM 1 FROM core.find_user_for_login('u' || ((i * 97) % 100000 + 1) || '@perf.local');
  END LOOP;
  avg_login := extract(epoch FROM clock_timestamp() - t0) * 1000 / 1000;

  t0 := clock_timestamp();
  FOR i IN 1..1000 LOOP
    PERFORM 1 FROM core.find_refresh_token(encode(sha256(convert_to('u' || ((i * 97) % 100000 + 1) || '@perf.local', 'UTF8')), 'hex'));
  END LOOP;
  avg_refresh := extract(epoch FROM clock_timestamp() - t0) * 1000 / 1000;

  t0 := clock_timestamp();
  purged := core.purge_expired_refresh_tokens();
  ms_purge := extract(epoch FROM clock_timestamp() - t0) * 1000;

  RAISE NOTICE 'find_user_for_login: media % ms | find_refresh_token: media % ms | purge (100 mil tokens, % apagados): % ms',
    round(avg_login, 3), round(avg_refresh, 3), purged, round(ms_purge, 1);
  IF avg_login > 5 THEN RAISE EXCEPTION 'find_user_for_login acima de 5 ms (media %)', avg_login; END IF;
  IF avg_refresh > 5 THEN RAISE EXCEPTION 'find_refresh_token acima de 5 ms (media %)', avg_refresh; END IF;
  IF purged <> 0 THEN RAISE EXCEPTION 'nenhum token da carga deveria estar vencido ha 30 dias (apagados %)', purged; END IF;
END $$;

-- Sob a RLS (papel da aplicação): a listagem paginada por nome usa ix_users_clinic_name, sem Sort
SET SESSION AUTHORIZATION crm_app;
SELECT set_config('app.clinic_id', '00000000-0000-0000-0000-000000000007', true) AS _ \gset
DO $$
DECLARE
  r json;
  p jsonb;
  ms numeric;
BEGIN
  -- (sem o auxiliar temporário: o schema pg_temp do administrador não é acessível ao crm_app)
  EXECUTE 'EXPLAIN (ANALYZE, FORMAT JSON) SELECT id, name, email, role, active FROM core.users ORDER BY name LIMIT 20' INTO r;
  p := r::jsonb;
  IF p::text NOT LIKE '%"Index Name": "ix_users_clinic_name"%' THEN
    RAISE EXCEPTION 'listagem de usuarios nao usa ix_users_clinic_name: %', p;
  END IF;
  IF p::text LIKE '%"Node Type": "Sort"%' THEN
    RAISE EXCEPTION 'listagem de usuarios usa Sort adicional: %', p;
  END IF;
  ms := (p -> 0 ->> 'Execution Time')::numeric;
  RAISE NOTICE 'listagem de usuarios (RLS): % ms', ms;
  IF ms > 50 THEN RAISE EXCEPTION 'listagem de usuarios lenta demais: % ms', ms; END IF;
END $$;
RESET SESSION AUTHORIZATION;

ROLLBACK;

SELECT 'performance_indexes: OK' AS resultado;
