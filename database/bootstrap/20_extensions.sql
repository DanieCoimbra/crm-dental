-- =====================================================================
-- Fase 1 / F01-session-foundation | 20_extensions.sql
-- Objetivo : instalar a extensão pg_trgm (busca de pacientes por trecho do nome) no schema
--            "extensions" e dar USAGE nesse schema ao crm_owner, que cria o índice GIN (F02).
-- Executar : UMA vez (idempotente), como administrador do projeto (postgres), DEPOIS de
--            00_roles_and_schema.sql. Manualmente com psql (ver database/README.md). NUNCA pela API.
-- Reverter : ver database/rollback/README.md (o bootstrap não é migration).
-- Uso      : psql -v ON_ERROR_STOP=1 -f 20_extensions.sql
-- Observação: o crm_app NÃO recebe USAGE em "extensions": o planejador escolhe o índice trigram
--            pelo operador LIKE, sem resolver nomes. No Supabase o schema já existe.
-- Plano B  : se a extensão não puder ser criada, remova o índice ix_patients_name_trgm da F02 e
--            mantenha só a busca por prefixo (ix_patients_name_prefix).
-- =====================================================================
\set ON_ERROR_STOP on

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_owner') THEN
    RAISE EXCEPTION 'extensions: execute antes 00_roles_and_schema.sql (crm_owner nao existe)';
  END IF;

  CREATE SCHEMA IF NOT EXISTS extensions;

  BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA extensions;
  EXCEPTION
    WHEN insufficient_privilege OR feature_not_supported OR undefined_file THEN
      RAISE EXCEPTION 'extensions: nao foi possivel criar pg_trgm (%). Plano B: so busca por prefixo (remover ix_patients_name_trgm)', SQLERRM;
  END;

  -- a extensão já existia em outro schema: o índice da F02 usa extensions.gin_trgm_ops
  IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                  WHERE e.extname = 'pg_trgm' AND n.nspname = 'extensions') THEN
    RAISE EXCEPTION 'extensions: pg_trgm precisa estar no schema extensions (esta em outro schema)';
  END IF;
END $$;

GRANT USAGE ON SCHEMA extensions TO crm_owner;

-- O crm_app não precisa de USAGE em "extensions" (o índice trigram é escolhido pelo operador LIKE). No
-- Supabase o schema pode ser legível por PUBLIC ou por outros papéis: avisa, sem abortar, para o operador decidir.
DO $$
DECLARE
  r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['crm_app', 'crm_backup', 'crm_definer'] LOOP
    IF has_schema_privilege(r, 'extensions', 'USAGE') THEN
      RAISE WARNING 'extensions: % tem USAGE no schema extensions (herdado de PUBLIC ou de outro papel); revise antes de ir para producao', r;
    END IF;
  END LOOP;
END $$;
