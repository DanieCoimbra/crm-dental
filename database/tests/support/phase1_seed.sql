-- =====================================================================
-- tests/support/phase1_seed.sql | Fase 1 (F02-F07)
-- Apoio dos testes da Fase 1. Incluir com `\ir support/phase1_seed.sql` DENTRO de uma transação
-- (BEGIN) aberta pelo teste, como administrador do banco de teste. Tudo é revertido no ROLLBACK.
--   * variáveis psql: clínicas (ca, cb) e usuários (ua_d, ua_r, ub_d, ub_r);
--   * macros de sessão (ctx_*): definem app.clinic_id, app.role e app.user_id como o backend faria;
--   * auxiliares temporários: pg_temp.expect_err e pg_temp.expect_rows.
-- Uso das macros:   :ctx_a_d \gset
-- =====================================================================
\set ca   'f1000000-0000-4000-8000-00000000000a'
\set cb   'f1000000-0000-4000-8000-00000000000b'
\set ua_d 'f1000000-0000-4000-8000-0000000000a1'
\set ua_r 'f1000000-0000-4000-8000-0000000000a2'
\set ub_d 'f1000000-0000-4000-8000-0000000000b1'
\set ub_r 'f1000000-0000-4000-8000-0000000000b2'

\set ctx_a_d 'SELECT set_config(''app.clinic_id'', ''f1000000-0000-4000-8000-00000000000a'', true) || set_config(''app.role'', ''DENTIST_ADMIN'', true) || set_config(''app.user_id'', ''f1000000-0000-4000-8000-0000000000a1'', true) AS _'
\set ctx_a_r 'SELECT set_config(''app.clinic_id'', ''f1000000-0000-4000-8000-00000000000a'', true) || set_config(''app.role'', ''RECEPTIONIST'', true) || set_config(''app.user_id'', ''f1000000-0000-4000-8000-0000000000a2'', true) AS _'
\set ctx_b_d 'SELECT set_config(''app.clinic_id'', ''f1000000-0000-4000-8000-00000000000b'', true) || set_config(''app.role'', ''DENTIST_ADMIN'', true) || set_config(''app.user_id'', ''f1000000-0000-4000-8000-0000000000b1'', true) AS _'
\set ctx_b_r 'SELECT set_config(''app.clinic_id'', ''f1000000-0000-4000-8000-00000000000b'', true) || set_config(''app.role'', ''RECEPTIONIST'', true) || set_config(''app.user_id'', ''f1000000-0000-4000-8000-0000000000b2'', true) AS _'
-- clínica A, dentista, mas com o usuário de outra clínica (autoria falsa entre clínicas)
\set ctx_a_d_user_b 'SELECT set_config(''app.clinic_id'', ''f1000000-0000-4000-8000-00000000000a'', true) || set_config(''app.role'', ''DENTIST_ADMIN'', true) || set_config(''app.user_id'', ''f1000000-0000-4000-8000-0000000000b1'', true) AS _'
-- sem nenhuma variável de sessão
\set ctx_none 'SELECT set_config(''app.clinic_id'', '''', true) || set_config(''app.role'', '''', true) || set_config(''app.user_id'', '''', true) AS _'
-- clínica e usuário válidos, mas sem papel
\set ctx_a_norole 'SELECT set_config(''app.clinic_id'', ''f1000000-0000-4000-8000-00000000000a'', true) || set_config(''app.role'', '''', true) || set_config(''app.user_id'', ''f1000000-0000-4000-8000-0000000000a1'', true) AS _'
-- clínica e papel válidos, mas sem usuário
\set ctx_a_d_nouser 'SELECT set_config(''app.clinic_id'', ''f1000000-0000-4000-8000-00000000000a'', true) || set_config(''app.role'', ''DENTIST_ADMIN'', true) || set_config(''app.user_id'', '''', true) AS _'

-- Esperar um SQLSTATE específico (cobre também a RLS: WITH CHECK e grants dão 42501)
CREATE FUNCTION pg_temp.expect_err(p_label text, p_state text, p_sql text) RETURNS void
  LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE p_sql;
  EXCEPTION WHEN OTHERS THEN
    IF SQLSTATE = p_state THEN RETURN; END IF;
    RAISE EXCEPTION '%: esperado SQLSTATE %, obtido % (%)', p_label, p_state, SQLSTATE, SQLERRM;
  END;
  RAISE EXCEPTION '%: deveria falhar com SQLSTATE %', p_label, p_state;
END $$;

-- Esperar um número de linhas lidas/afetadas (SELECT, INSERT, UPDATE ou DELETE simples)
CREATE FUNCTION pg_temp.expect_rows(p_label text, p_sql text, p_expected bigint) RETURNS void
  LANGUAGE plpgsql AS $$
DECLARE
  n bigint;
BEGIN
  EXECUTE p_sql;
  GET DIAGNOSTICS n = ROW_COUNT;
  IF n <> p_expected THEN
    RAISE EXCEPTION '%: esperadas % linhas, obtidas %', p_label, p_expected, n;
  END IF;
END $$;

-- Fixtures mínimas (como administrador: ignora a RLS): duas clínicas, um dentista e uma recepcionista em cada
INSERT INTO core.clinics (id, name) VALUES (:'ca', 'Clinica A (fase 1)'), (:'cb', 'Clinica B (fase 1)');
INSERT INTO core.users (id, clinic_id, name, email, password_hash, role, created_by) VALUES
  (:'ua_d', :'ca', 'Dentista A', 'dentista.a@fase1.test', 'hash', 'DENTIST_ADMIN', NULL),
  (:'ua_r', :'ca', 'Recep A',    'recep.a@fase1.test',    'hash', 'RECEPTIONIST',  :'ua_d'),
  (:'ub_d', :'cb', 'Dentista B', 'dentista.b@fase1.test', 'hash', 'DENTIST_ADMIN', NULL),
  (:'ub_r', :'cb', 'Recep B',    'recep.b@fase1.test',    'hash', 'RECEPTIONIST',  :'ub_d');
