-- =====================================================================
-- V002__core_tenant_function.sql | F02-tenant-and-rls-foundation
-- Objetivo : função de tenant (core.current_clinic_id) e funções de trigger
--            reutilizáveis (updated_at e colunas imutáveis) usadas por toda
--            tabela de negócio.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U002__core_tenant_function.sql
-- Receita de tabela de negócio: docs/features/banco/F02-tenant-and-rls-foundation/contract.md
-- =====================================================================

-- Tenant da transação, definido pelo backend com set_config('app.clinic_id', '<uuid>', true).
-- Sem a variável (ou vazia) devolve NULL e nenhuma política casa: falha fechada.
-- Valor que não seja UUID gera erro de cast (22P02) e aborta a transação.
-- STABLE (nunca IMMUTABLE): lê configuração de sessão.
CREATE FUNCTION core.current_clinic_id() RETURNS uuid
  LANGUAGE sql STABLE PARALLEL SAFE
  SET search_path = pg_catalog
AS $$ SELECT NULLIF(current_setting('app.clinic_id', true), '')::uuid $$;

REVOKE EXECUTE ON FUNCTION core.current_clinic_id() FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION core.current_clinic_id() TO crm_app;

-- Trigger BEFORE UPDATE: mantém updated_at.
CREATE FUNCTION core.set_updated_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog
AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

-- Trigger BEFORE UPDATE com argumentos = nomes das colunas imutáveis.
--   CREATE TRIGGER trg_forbid_immutable BEFORE UPDATE ON core.t
--     FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'clinic_id');
-- Erro 23514 (check_violation) se alguma coluna listada mudar; erro 42703 (undefined_column) se
-- o nome listado não existir na tabela (erro de programação, nunca silencioso).
-- Sem SQL dinâmico: compara to_jsonb(OLD) com to_jsonb(NEW).
CREATE FUNCTION core.forbid_immutable_columns() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog
AS $$
DECLARE
  col text;
  old_row jsonb := to_jsonb(OLD);
  new_row jsonb := to_jsonb(NEW);
BEGIN
  FOREACH col IN ARRAY TG_ARGV LOOP
    IF NOT (new_row ? col) THEN
      RAISE EXCEPTION 'coluna inexistente em %.%: %', TG_TABLE_SCHEMA, TG_TABLE_NAME, col
        USING ERRCODE = '42703';
    END IF;
    IF (old_row -> col) IS DISTINCT FROM (new_row -> col) THEN
      RAISE EXCEPTION 'coluna imutavel: %.%.%', TG_TABLE_SCHEMA, TG_TABLE_NAME, col
        USING ERRCODE = '23514';
    END IF;
  END LOOP;
  RETURN NEW;
END;
$$;

-- Funções de trigger não são chamadas por papéis de aplicação; o EXECUTE é verificado de quem
-- cria o trigger (crm_owner). Nenhuma concessão.
REVOKE EXECUTE ON FUNCTION core.set_updated_at() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.forbid_immutable_columns() FROM PUBLIC;
