-- =====================================================================
-- V005__core_refresh_tokens.sql | F03-core-tables
-- Objetivo : tabela core.refresh_tokens (sessões rotativas). O token é guardado só como SHA-256
--            hexadecimal; a FK composta (user_id, clinic_id) impede um token de pertencer a
--            usuário de outra clínica.
-- Executar : Flyway, como crm_owner.
-- Reverter : database/rollback/U005__core_refresh_tokens.sql
-- Depende  : V004 (core.users e ux_users_id_clinic), V003, V002.
-- =====================================================================

CREATE TABLE core.refresh_tokens (
  id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id   uuid        NOT NULL REFERENCES core.clinics (id) ON DELETE CASCADE,
  user_id     uuid        NOT NULL,
  family_id   uuid        NOT NULL,
  token_hash  varchar(64) NOT NULL,
  expires_at  timestamptz NOT NULL,
  used_at     timestamptz,
  revoked_at  timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_refresh_user_clinic FOREIGN KEY (user_id, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE CASCADE,
  CONSTRAINT ck_refresh_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

-- Refresh e logout por hash
CREATE UNIQUE INDEX ux_refresh_hash    ON core.refresh_tokens (token_hash);
-- Revogar sessões de um usuário
CREATE INDEX        ix_refresh_user    ON core.refresh_tokens (user_id);
-- Revogar a cadeia em caso de reuso
CREATE INDEX        ix_refresh_family  ON core.refresh_tokens (family_id);
-- Limpeza de vencidos (F04)
CREATE INDEX        ix_refresh_expires ON core.refresh_tokens (expires_at);
-- Suporte à RLS e à FK de clinics
CREATE INDEX        ix_refresh_clinic  ON core.refresh_tokens (clinic_id);

CREATE TRIGGER trg_refresh_forbid_immutable
  BEFORE UPDATE ON core.refresh_tokens
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns(
    'id', 'clinic_id', 'user_id', 'family_id', 'token_hash', 'created_at');

ALTER TABLE core.refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.refresh_tokens FORCE  ROW LEVEL SECURITY;

CREATE POLICY refresh_sel ON core.refresh_tokens
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id());

CREATE POLICY refresh_ins ON core.refresh_tokens
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id());

CREATE POLICY refresh_upd ON core.refresh_tokens
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id())
  WITH CHECK (clinic_id = core.current_clinic_id());

CREATE POLICY refresh_backup_sel ON core.refresh_tokens
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE para crm_app: a limpeza de vencidos é feita só pela função da F04
GRANT SELECT, INSERT, UPDATE ON core.refresh_tokens TO crm_app;
GRANT SELECT                 ON core.refresh_tokens TO crm_backup;
