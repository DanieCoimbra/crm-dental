-- =====================================================================
-- tests/clinic_timezone.sql | Fase 2 / F01-extensions-and-timezone
-- Teste 73 da spec global da Fase 2: fuso da clínica (default, validação do nome, quem pode trocar, UPDATE que reenvia
-- o mesmo valor) e a presença do btree_gist no schema extensions.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 23514 fuso inexistente ou curto; 42501 papel diferente de DENTIST_ADMIN trocando o fuso.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase1_seed.sql

-- btree_gist instalada no schema extensions (F01: sem plano B)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                  WHERE e.extname = 'btree_gist' AND n.nspname = 'extensions') THEN
    RAISE EXCEPTION 'T83: btree_gist deve estar instalada no schema extensions';
  END IF;
  -- e o USAGE em extensions segue só com o crm_owner (a aplicação não resolve nomes desse schema)
  IF has_schema_privilege('crm_app', 'extensions', 'USAGE') OR has_schema_privilege('crm_backup', 'extensions', 'USAGE')
     OR has_schema_privilege('crm_definer', 'extensions', 'USAGE') THEN
    RAISE EXCEPTION 'T83: crm_app, crm_backup e crm_definer nao devem ter USAGE em extensions';
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- Default e catálogo
-- ---------------------------------------------------------------------
DO $$
BEGIN
  IF (SELECT timezone FROM core.clinics WHERE id = 'f1000000-0000-4000-8000-00000000000a') <> 'America/Sao_Paulo' THEN
    RAISE EXCEPTION 'T73: clinica nova deve ter o default America/Sao_Paulo';
  END IF;
  -- ADD COLUMN com default constante: linhas anteriores à V014 assumem o default sem reescrever a tabela
  IF NOT EXISTS (SELECT 1 FROM pg_attribute a
                  WHERE a.attrelid = 'core.clinics'::regclass AND a.attname = 'timezone' AND a.attnotnull
                    AND a.atthasmissing AND a.attmissingval::text = '{America/Sao_Paulo}') THEN
    RAISE EXCEPTION 'T73: clinics.timezone deve ser NOT NULL com valor padrao constante (metadado, sem reescrita)';
  END IF;
END $$;

-- INSERT (administrador): o trigger só valida o nome
INSERT INTO core.clinics (id, name, timezone) VALUES ('f1000000-0000-4000-8000-0000000000c1', 'Clinica UTC', 'UTC');
SELECT pg_temp.expect_err('T73 INSERT com fuso inexistente', '23514',
  $q$INSERT INTO core.clinics (id, name, timezone) VALUES (gen_random_uuid(), 'Clinica Marte', 'Mars/Phobos')$q$);
SELECT pg_temp.expect_err('T73 INSERT com fuso de 2 caracteres', '23514',
  $q$INSERT INTO core.clinics (id, name, timezone) VALUES (gen_random_uuid(), 'Clinica Curta', 'ab')$q$);
SELECT pg_temp.expect_err('T73 INSERT com fuso mal escrito', '23514',
  $q$INSERT INTO core.clinics (id, name, timezone) VALUES (gen_random_uuid(), 'Clinica Typo', 'America/Sao Paulo')$q$);

-- ---------------------------------------------------------------------
-- UPDATE como crm_app
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_app;

:ctx_a_d \gset
SELECT pg_temp.expect_rows('T73 dentista troca para UTC',
  format('UPDATE core.clinics SET timezone = %L WHERE id = %L', 'UTC', :'ca'), 1);
SELECT pg_temp.expect_rows('T73 dentista troca para um fuso IANA valido',
  format('UPDATE core.clinics SET timezone = %L WHERE id = %L', 'America/Manaus', :'ca'), 1);
SELECT pg_temp.expect_err('T73 dentista: fuso inexistente', '23514',
  format('UPDATE core.clinics SET timezone = %L WHERE id = %L', 'Mars/Phobos', :'ca'));
SELECT pg_temp.expect_err('T73 dentista: fuso de 2 caracteres', '23514',
  format('UPDATE core.clinics SET timezone = %L WHERE id = %L', 'ab', :'ca'));

:ctx_a_r \gset
SELECT pg_temp.expect_err('T73 recepcao nao troca o fuso', '42501',
  format('UPDATE core.clinics SET timezone = %L WHERE id = %L', 'UTC', :'ca'));
-- o JPA reenvia todas as colunas em todo UPDATE: reenviar o valor ATUAL passa para qualquer papel (sem consulta ao catalogo)
SELECT pg_temp.expect_rows('T73 recepcao reenvia o mesmo fuso',
  format('UPDATE core.clinics SET timezone = timezone, name = name, version = version + 1 WHERE id = %L', :'ca'), 1);
-- e um UPDATE que nem cita o fuso também passa
SELECT pg_temp.expect_rows('T73 recepcao altera o telefone sem tocar no fuso',
  format('UPDATE core.clinics SET phone = %L WHERE id = %L', '1133334444', :'ca'), 1);

:ctx_a_norole \gset
SELECT pg_temp.expect_err('T73 sem papel nao troca o fuso', '42501',
  format('UPDATE core.clinics SET timezone = %L WHERE id = %L', 'UTC', :'ca'));

-- outra clínica: o dentista de B não alcança a clínica A (RLS de clinics, Fase 0)
:ctx_b_d \gset
SELECT pg_temp.expect_rows('T73 dentista de B nao altera o fuso da clinica A',
  format('UPDATE core.clinics SET timezone = %L WHERE id = %L', 'UTC', :'ca'), 0);

RESET SESSION AUTHORIZATION;

-- o fuso gravado é o último válido do dentista de A
DO $$
BEGIN
  IF (SELECT timezone FROM core.clinics WHERE id = 'f1000000-0000-4000-8000-00000000000a') <> 'America/Manaus' THEN
    RAISE EXCEPTION 'T73: o fuso da clinica A deveria ser America/Manaus';
  END IF;
  IF (SELECT timezone FROM core.clinics WHERE id = 'f1000000-0000-4000-8000-00000000000b') <> 'America/Sao_Paulo' THEN
    RAISE EXCEPTION 'T73: o fuso da clinica B nao pode ter mudado';
  END IF;
  IF (SELECT p.prosecdef FROM pg_proc p WHERE p.oid = 'core.validate_clinic_timezone()'::regprocedure) THEN
    RAISE EXCEPTION 'T73: validate_clinic_timezone deve ser SECURITY INVOKER';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'clinic_timezone: OK' AS resultado;
