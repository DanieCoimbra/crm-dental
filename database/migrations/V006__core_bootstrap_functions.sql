-- =====================================================================
-- V006__core_bootstrap_functions.sql | F04-bootstrap-functions
-- Objetivo : funções SECURITY DEFINER para o que acontece ANTES de existir tenant:
--            core.find_user_for_login, core.find_refresh_token e
--            core.purge_expired_refresh_tokens. Pertencem a crm_definer (NOLOGIN, sem BYPASSRLS,
--            não dono das tabelas), que acessa os dados por políticas RLS nomeadas só para ele.
-- Executar : Flyway, como crm_owner, numa única transação (crm_owner é membro de crm_definer,
--            concedido no bootstrap).
-- Reverter : database/rollback/U006__core_bootstrap_functions.sql
-- Depende  : V003, V004, V005, V001 (USAGE de crm_definer em core).
-- Segurança: search_path fixo (pg_catalog, pg_temp), sem SQL dinâmico, retorno mínimo,
--            EXECUTE só para crm_app.
-- =====================================================================

-- ALTER FUNCTION ... OWNER TO exige CREATE no schema para o novo dono. A concessão vale apenas
-- dentro desta transação e é revogada no fim.
GRANT CREATE ON SCHEMA core TO crm_definer;

-- ---------------------------------------------------------------------
-- Privilégios mínimos do crm_definer (leitura por colunas / linhas)
-- ---------------------------------------------------------------------
-- users: somente as colunas necessárias ao login
GRANT SELECT (id, clinic_id, email, password_hash, role, active) ON core.users TO crm_definer;
-- refresh_tokens: leitura e remoção (limpeza de vencidos)
GRANT SELECT, DELETE ON core.refresh_tokens TO crm_definer;

-- Com FORCE RLS, o acesso precisa de política explícita para o papel.
CREATE POLICY users_definer_sel ON core.users
  FOR SELECT TO crm_definer
  USING (true);

CREATE POLICY refresh_definer_sel ON core.refresh_tokens
  FOR SELECT TO crm_definer
  USING (true);

-- Só pode apagar tokens vencidos há mais de 30 dias
CREATE POLICY refresh_definer_del ON core.refresh_tokens
  FOR DELETE TO crm_definer
  USING (expires_at < now() - interval '30 days');

-- ---------------------------------------------------------------------
-- Login: localizar usuário por e-mail (sem tenant)
-- ---------------------------------------------------------------------
CREATE FUNCTION core.find_user_for_login(p_email varchar)
RETURNS TABLE (id uuid, clinic_id uuid, password_hash varchar, role varchar, active boolean)
LANGUAGE sql STABLE STRICT SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
  SELECT u.id, u.clinic_id, u.password_hash, u.role, u.active
    FROM core.users u
   WHERE u.email = pg_catalog.lower(p_email)
$$;

-- ---------------------------------------------------------------------
-- Refresh/logout: localizar token pelo hash (sem tenant)
-- ---------------------------------------------------------------------
CREATE FUNCTION core.find_refresh_token(p_hash varchar)
RETURNS TABLE (id uuid, clinic_id uuid, user_id uuid, family_id uuid,
               expires_at timestamptz, used_at timestamptz, revoked_at timestamptz)
LANGUAGE sql STABLE STRICT SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
  SELECT t.id, t.clinic_id, t.user_id, t.family_id, t.expires_at, t.used_at, t.revoked_at
    FROM core.refresh_tokens t
   WHERE t.token_hash = p_hash
$$;

-- ---------------------------------------------------------------------
-- Limpeza: apaga tokens vencidos há mais de 30 dias (todas as clínicas) e devolve a contagem
-- ---------------------------------------------------------------------
CREATE FUNCTION core.purge_expired_refresh_tokens()
RETURNS integer
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  n integer;
BEGIN
  DELETE FROM core.refresh_tokens
   WHERE expires_at < pg_catalog.now() - interval '30 days';
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END;
$$;

-- ---------------------------------------------------------------------
-- EXECUTE, dono e fechamento da concessão temporária
-- ---------------------------------------------------------------------
-- Primeiro o EXECUTE, enquanto crm_owner ainda é o dono: crm_owner é NOINHERIT, então ser
-- membro de crm_definer permite ASSUMIR o papel (suficiente para ALTER ... OWNER TO), mas não
-- concede os privilégios de dono. O PostgreSQL transfere as entradas de ACL junto com o dono.
REVOKE EXECUTE ON FUNCTION core.find_user_for_login(varchar)   FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.find_refresh_token(varchar)    FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.purge_expired_refresh_tokens() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION core.find_user_for_login(varchar)   TO crm_app;
GRANT EXECUTE ON FUNCTION core.find_refresh_token(varchar)    TO crm_app;
GRANT EXECUTE ON FUNCTION core.purge_expired_refresh_tokens() TO crm_app;

ALTER FUNCTION core.find_user_for_login(varchar)       OWNER TO crm_definer;
ALTER FUNCTION core.find_refresh_token(varchar)        OWNER TO crm_definer;
ALTER FUNCTION core.purge_expired_refresh_tokens()     OWNER TO crm_definer;

REVOKE CREATE ON SCHEMA core FROM crm_definer;
