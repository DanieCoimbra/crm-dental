-- =====================================================================
-- tests/constraints.sql | F03-core-tables
-- Testes 7 a 12: constraints, imutabilidade, FKs, ON DELETE e updated_at.
-- Executa como administrador (superusuário ignora a RLS), o que ISOLA as constraints e os
-- triggers do efeito das políticas. Tudo revertido ao final.
-- =====================================================================
\set ON_ERROR_STOP on

-- Auxiliares (temporários): esperar um SQLSTATE específico
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

\o /dev/null
BEGIN;

-- Fixtures: duas clínicas; dentista + recepcionista na 1; dentista na 2
INSERT INTO core.clinics (id, name) VALUES
  ('c1000000-0000-0000-0000-000000000001', 'Clinica 1'),
  ('c2000000-0000-0000-0000-000000000002', 'Clinica 2'),
  ('c3000000-0000-0000-0000-000000000003', 'Clinica 3 (sem dentista)');
INSERT INTO core.users (id, clinic_id, name, email, password_hash, role, created_by, updated_at) VALUES
  ('d1000000-0000-0000-0000-000000000001', 'c1000000-0000-0000-0000-000000000001', 'Dentista 1', 'd1@teste.local', 'h', 'DENTIST_ADMIN', NULL, '2000-01-01T00:00:00Z'),
  ('a1000000-0000-0000-0000-000000000001', 'c1000000-0000-0000-0000-000000000001', 'Recep 1',    'r1@teste.local', 'h', 'RECEPTIONIST',  'd1000000-0000-0000-0000-000000000001', '2000-01-01T00:00:00Z'),
  ('d2000000-0000-0000-0000-000000000002', 'c2000000-0000-0000-0000-000000000002', 'Dentista 2', 'd2@teste.local', 'h', 'DENTIST_ADMIN', NULL, now()),
  ('a3000000-0000-0000-0000-000000000003', 'c3000000-0000-0000-0000-000000000003', 'Recep 3',    'r3@teste.local', 'h', 'RECEPTIONIST',  NULL, now());
INSERT INTO core.refresh_tokens (id, clinic_id, user_id, family_id, token_hash, expires_at) VALUES
  ('f1000000-0000-0000-0000-000000000001', 'c1000000-0000-0000-0000-000000000001', 'd1000000-0000-0000-0000-000000000001',
   gen_random_uuid(), repeat('a', 64), now() + interval '7 days');

-- T7: e-mail ------------------------------------------------------------------
SELECT pg_temp.expect_err('T7 e-mail duplicado entre clinicas', '23505',
  $q$INSERT INTO core.users (clinic_id, name, email, password_hash, role)
     VALUES ('c2000000-0000-0000-0000-000000000002', 'Outro', 'd1@teste.local', 'h', 'RECEPTIONIST')$q$);
SELECT pg_temp.expect_err('T7 e-mail com maiusculas', '23514',
  $q$INSERT INTO core.users (clinic_id, name, email, password_hash, role)
     VALUES ('c2000000-0000-0000-0000-000000000002', 'Outro', 'Maiuscula@Teste.Local', 'h', 'RECEPTIONIST')$q$);
SELECT pg_temp.expect_err('T7 e-mail acima de 254 caracteres', '22001',
  $q$INSERT INTO core.users (clinic_id, name, email, password_hash, role)
     VALUES ('c2000000-0000-0000-0000-000000000002', 'Outro', repeat('a', 250) || '@x.local', 'h', 'RECEPTIONIST')$q$);

-- T8: um dentista por clínica; dentista sempre ativo ---------------------------
SELECT pg_temp.expect_err('T8 segundo dentista na mesma clinica', '23505',
  $q$INSERT INTO core.users (clinic_id, name, email, password_hash, role)
     VALUES ('c1000000-0000-0000-0000-000000000001', 'Dentista extra', 'extra@teste.local', 'h', 'DENTIST_ADMIN')$q$);
SELECT pg_temp.expect_err('T8 dentista inativo (INSERT, clinica sem dentista)', '23514',
  $q$INSERT INTO core.users (clinic_id, name, email, password_hash, role, active)
     VALUES ('c3000000-0000-0000-0000-000000000003', 'Inativo', 'inativo@teste.local', 'h', 'DENTIST_ADMIN', false)$q$);
SELECT pg_temp.expect_err('T8 desativar o dentista (UPDATE)', '23514',
  $q$UPDATE core.users SET active = false WHERE id = 'd1000000-0000-0000-0000-000000000001'$q$);
DO $$
BEGIN
  UPDATE core.users SET active = false WHERE id = 'a1000000-0000-0000-0000-000000000001';
  IF (SELECT active FROM core.users WHERE id = 'a1000000-0000-0000-0000-000000000001') THEN
    RAISE EXCEPTION 'T8: recepcionista deveria poder ser desativado';
  END IF;
  UPDATE core.users SET active = true WHERE id = 'a1000000-0000-0000-0000-000000000001';
END $$;

-- T9: papel válido e colunas imutáveis -----------------------------------------
SELECT pg_temp.expect_err('T9 role invalido', '23514',
  $q$INSERT INTO core.users (clinic_id, name, email, password_hash, role)
     VALUES ('c2000000-0000-0000-0000-000000000002', 'Role ruim', 'role@teste.local', 'h', 'ADMIN')$q$);
SELECT pg_temp.expect_err('T9 alterar role (promover, clinica sem dentista)', '23514',
  $q$UPDATE core.users SET role = 'DENTIST_ADMIN' WHERE id = 'a3000000-0000-0000-0000-000000000003'$q$);
SELECT pg_temp.expect_err('T9 alterar role (rebaixar o dentista)', '23514',
  $q$UPDATE core.users SET role = 'RECEPTIONIST' WHERE id = 'd2000000-0000-0000-0000-000000000002'$q$);
SELECT pg_temp.expect_err('T9 alterar clinic_id do usuario (trigger, sem RLS)', '23514',
  $q$UPDATE core.users SET clinic_id = 'c2000000-0000-0000-0000-000000000002' WHERE id = 'a1000000-0000-0000-0000-000000000001'$q$);
SELECT pg_temp.expect_err('T9 alterar id do usuario', '23514',
  $q$UPDATE core.users SET id = gen_random_uuid() WHERE id = 'a1000000-0000-0000-0000-000000000001'$q$);
SELECT pg_temp.expect_err('T9 alterar created_at do usuario', '23514',
  $q$UPDATE core.users SET created_at = now() + interval '1 day' WHERE id = 'a1000000-0000-0000-0000-000000000001'$q$);
SELECT pg_temp.expect_err('T9 alterar id da clinica', '23514',
  $q$UPDATE core.clinics SET id = gen_random_uuid() WHERE id = 'c1000000-0000-0000-0000-000000000001'$q$);
SELECT pg_temp.expect_err('T9 alterar created_at da clinica', '23514',
  $q$UPDATE core.clinics SET created_at = now() + interval '1 day' WHERE id = 'c1000000-0000-0000-0000-000000000001'$q$);
SELECT pg_temp.expect_err('T9 nome curto (usuario)', '23514',
  $q$INSERT INTO core.users (clinic_id, name, email, password_hash, role)
     VALUES ('c2000000-0000-0000-0000-000000000002', 'X', 'curto@teste.local', 'h', 'RECEPTIONIST')$q$);
SELECT pg_temp.expect_err('T9 nome curto (clinica)', '23514',
  $q$INSERT INTO core.clinics (name) VALUES ('X')$q$);
SELECT pg_temp.expect_err('T9 nome nulo (clinica)', '23502',
  $q$INSERT INTO core.clinics (name) VALUES (NULL)$q$);
SELECT pg_temp.expect_err('T9 clinic_id nulo (usuario)', '23502',
  $q$INSERT INTO core.users (clinic_id, name, email, password_hash, role)
     VALUES (NULL, 'Sem clinica', 'semclinica@teste.local', 'h', 'RECEPTIONIST')$q$);
SELECT pg_temp.expect_err('T9 usuario em clinica inexistente', '23503',
  $q$INSERT INTO core.users (clinic_id, name, email, password_hash, role)
     VALUES (gen_random_uuid(), 'Orfao', 'orfao@teste.local', 'h', 'RECEPTIONIST')$q$);

-- T10: token_hash -----------------------------------------------------------------
SELECT pg_temp.expect_err('T10 hash em maiusculas', '23514',
  $q$INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
     VALUES ('c1000000-0000-0000-0000-000000000001', 'd1000000-0000-0000-0000-000000000001', gen_random_uuid(), repeat('A', 64), now())$q$);
SELECT pg_temp.expect_err('T10 hash com 63 caracteres', '23514',
  $q$INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
     VALUES ('c1000000-0000-0000-0000-000000000001', 'd1000000-0000-0000-0000-000000000001', gen_random_uuid(), repeat('a', 63), now())$q$);
SELECT pg_temp.expect_err('T10 hash nao hexadecimal', '23514',
  $q$INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
     VALUES ('c1000000-0000-0000-0000-000000000001', 'd1000000-0000-0000-0000-000000000001', gen_random_uuid(), repeat('g', 64), now())$q$);
SELECT pg_temp.expect_err('T10 hash com 65 caracteres', '22001',
  $q$INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
     VALUES ('c1000000-0000-0000-0000-000000000001', 'd1000000-0000-0000-0000-000000000001', gen_random_uuid(), repeat('a', 65), now())$q$);
SELECT pg_temp.expect_err('T10 hash duplicado', '23505',
  $q$INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
     VALUES ('c1000000-0000-0000-0000-000000000001', 'd1000000-0000-0000-0000-000000000001', gen_random_uuid(), repeat('a', 64), now())$q$);
SELECT pg_temp.expect_err('T10 alterar token_hash', '23514',
  $q$UPDATE core.refresh_tokens SET token_hash = repeat('b', 64)$q$);
SELECT pg_temp.expect_err('T10 alterar family_id', '23514',
  $q$UPDATE core.refresh_tokens SET family_id = gen_random_uuid()$q$);
SELECT pg_temp.expect_err('T10 alterar user_id', '23514',
  $q$UPDATE core.refresh_tokens SET user_id = 'a1000000-0000-0000-0000-000000000001'$q$);
SELECT pg_temp.expect_err('T10 alterar clinic_id do token', '23514',
  $q$UPDATE core.refresh_tokens SET clinic_id = 'c2000000-0000-0000-0000-000000000002'$q$);
SELECT pg_temp.expect_err('T10 expires_at nulo', '23502',
  $q$INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
     VALUES ('c1000000-0000-0000-0000-000000000001', 'd1000000-0000-0000-0000-000000000001', gen_random_uuid(), repeat('d', 64), NULL)$q$);

-- rotação/revogação (UPDATE de used_at e revoked_at) continua permitida
DO $$
BEGIN
  UPDATE core.refresh_tokens SET used_at = now(), revoked_at = now()
   WHERE id = 'f1000000-0000-0000-0000-000000000001';
  IF NOT FOUND THEN RAISE EXCEPTION 'T10: used_at/revoked_at deveriam ser atualizaveis'; END IF;
END $$;

-- T11: token de usuário de outra clínica (FK composta) ----------------------------
SELECT pg_temp.expect_err('T11 token com usuario de outra clinica', '23503',
  $q$INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
     VALUES ('c2000000-0000-0000-0000-000000000002', 'd1000000-0000-0000-0000-000000000001', gen_random_uuid(), repeat('e', 64), now())$q$);
SELECT pg_temp.expect_err('T11 token com usuario inexistente', '23503',
  $q$INSERT INTO core.refresh_tokens (clinic_id, user_id, family_id, token_hash, expires_at)
     VALUES ('c1000000-0000-0000-0000-000000000001', gen_random_uuid(), gen_random_uuid(), repeat('f', 64), now())$q$);

-- T12: updated_at muda a cada UPDATE (usuarios e clinicas) ------------------------
UPDATE core.users SET name = 'Recep 1 v2' WHERE id = 'a1000000-0000-0000-0000-000000000001';
DO $$
BEGIN
  IF (SELECT updated_at FROM core.users WHERE id = 'a1000000-0000-0000-0000-000000000001') <= '2000-01-01T00:00:00Z' THEN
    RAISE EXCEPTION 'T12: users.updated_at nao mudou';
  END IF;
END $$;
UPDATE core.clinics SET name = 'Clinica 1 v2' WHERE id = 'c1000000-0000-0000-0000-000000000001';
DO $$
BEGIN
  IF (SELECT updated_at FROM core.clinics WHERE id = 'c1000000-0000-0000-0000-000000000001') < (SELECT created_at FROM core.clinics WHERE id = 'c1000000-0000-0000-0000-000000000001') THEN
    RAISE EXCEPTION 'T12: clinics.updated_at inconsistente';
  END IF;
  -- o cliente não consegue forjar updated_at: o trigger sempre prevalece
  UPDATE core.users SET updated_at = '1999-01-01T00:00:00Z' WHERE id = 'a1000000-0000-0000-0000-000000000001';
  IF (SELECT updated_at FROM core.users WHERE id = 'a1000000-0000-0000-0000-000000000001') < '2020-01-01T00:00:00Z' THEN
    RAISE EXCEPTION 'T12: updated_at foi forjado pelo cliente';
  END IF;
END $$;

-- ON DELETE ------------------------------------------------------------------------
SELECT pg_temp.expect_err('FK clinica com usuarios (RESTRICT)', '23503',
  $q$DELETE FROM core.clinics WHERE id = 'c1000000-0000-0000-0000-000000000001'$q$);

DO $$
DECLARE n bigint;
BEGIN
  -- apagar usuário: tokens somem (CASCADE) e created_by vira NULL (SET NULL)
  DELETE FROM core.users WHERE id = 'd1000000-0000-0000-0000-000000000001';
  SELECT count(*) INTO n FROM core.refresh_tokens WHERE user_id = 'd1000000-0000-0000-0000-000000000001';
  IF n <> 0 THEN RAISE EXCEPTION 'ON DELETE CASCADE nao removeu os tokens (%)', n; END IF;
  IF (SELECT created_by FROM core.users WHERE id = 'a1000000-0000-0000-0000-000000000001') IS NOT NULL THEN
    RAISE EXCEPTION 'ON DELETE SET NULL nao limpou created_by';
  END IF;
  -- apagar a clínica (sem usuários) funciona como administrador
  DELETE FROM core.users WHERE clinic_id = 'c1000000-0000-0000-0000-000000000001';
  DELETE FROM core.clinics WHERE id = 'c1000000-0000-0000-0000-000000000001';
END $$;

ROLLBACK;
\o

SELECT 'constraints: OK' AS resultado;
