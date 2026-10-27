-- =====================================================================
-- V031__core_users_cro.sql | Fase 4 / F01-professional-cro
-- Objetivo : CRO e UF do dentista em core.users (opcionais, os dois ou nenhum, só para DENTIST_ADMIN). Só o próprio dono altera o seu
--            CRO (trigger): a RLS de users filtra por clinic_id e não enxerga colunas.
-- Executar : scripts/apply_migrations.sh, como crm_owner, numa única transação.
-- Reverter : database/rollback/U031__core_users_cro.sql
-- Depende  : V004 (users), V007 (core.current_user_id).
-- Erros    : 23514 CRO fora do formato, par incompleto, recepcionista com CRO; 42501 alterar o CRO de outro usuário.
-- Observações:
--   * Migration aditiva: ADD COLUMN nulo, sem default, sem reescrever a tabela. Usuários existentes ficam com CRO nulo.
--   * O GRANT UPDATE de users (V004) é da tabela inteira: a guarda de coluna é o trigger trg_users_guard_cro.
--   * Funções da V006 selecionam colunas explícitas de users: não mudam.
-- =====================================================================

ALTER TABLE core.users ADD COLUMN cro_number varchar(15);
ALTER TABLE core.users ADD COLUMN cro_state  varchar(2);

ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_pair  CHECK ((cro_number IS NULL) = (cro_state IS NULL));
ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_role  CHECK (cro_number IS NULL OR role = 'DENTIST_ADMIN');
ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_fmt   CHECK (cro_number IS NULL OR cro_number ~ '^[0-9]{1,15}$');
ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_state CHECK (cro_state IS NULL OR cro_state IN
  ('AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG', 'PA', 'PB', 'PR', 'PE', 'PI', 'RJ', 'RN', 'RS', 'RO',
   'RR', 'SC', 'SP', 'SE', 'TO'));

-- Só o próprio usuário altera o seu CRO (sessão sem app.user_id também é negada)
CREATE FUNCTION core.guard_user_cro() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF (NEW.cro_number IS DISTINCT FROM OLD.cro_number OR NEW.cro_state IS DISTINCT FROM OLD.cro_state)
     AND core.current_user_id() IS DISTINCT FROM NEW.id THEN
    RAISE EXCEPTION 'apenas o proprio usuario altera o seu CRO' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION core.guard_user_cro() FROM PUBLIC;

CREATE TRIGGER trg_users_guard_cro
  BEFORE UPDATE ON core.users
  FOR EACH ROW EXECUTE FUNCTION core.guard_user_cro();
