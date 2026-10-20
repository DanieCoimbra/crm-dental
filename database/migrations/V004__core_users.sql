-- =====================================================================
-- V004__core_users.sql | F03-core-tables
-- Objetivo : tabela core.users com as invariantes de usuário no banco (e-mail único global em
--            minúsculas, um dentista por clínica, dentista sempre ativo, papel imutável),
--            RLS por clinic_id, triggers e grants mínimos.
-- Executar : Flyway, como crm_owner.
-- Reverter : database/rollback/U004__core_users.sql
-- Depende  : V003 (core.clinics), V002.
-- =====================================================================

CREATE TABLE core.users (
  id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id      uuid         NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  name           varchar(120) NOT NULL,
  email          varchar(254) NOT NULL,
  password_hash  varchar(100) NOT NULL,
  role           varchar(20)  NOT NULL,
  active         boolean      NOT NULL DEFAULT true,
  created_by     uuid         REFERENCES core.users (id) ON DELETE SET NULL,
  created_at     timestamptz  NOT NULL DEFAULT now(),
  updated_at     timestamptz  NOT NULL DEFAULT now(),
  version        bigint       NOT NULL DEFAULT 0,
  CONSTRAINT ck_users_name           CHECK (char_length(name) >= 2),
  CONSTRAINT ck_users_email_lower    CHECK (email = lower(email)),
  CONSTRAINT ck_users_role           CHECK (role IN ('DENTIST_ADMIN', 'RECEPTIONIST')),
  CONSTRAINT ck_users_dentist_active CHECK (role <> 'DENTIST_ADMIN' OR active)
);

-- Login por e-mail e unicidade global
CREATE UNIQUE INDEX ux_users_email       ON core.users (email);
-- Um dentista por clínica
CREATE UNIQUE INDEX ux_users_one_dentist ON core.users (clinic_id) WHERE role = 'DENTIST_ADMIN';
-- Alvo da FK composta de refresh_tokens
CREATE UNIQUE INDEX ux_users_id_clinic   ON core.users (id, clinic_id);
-- Listagem paginada por nome dentro da clínica (F05 do backend)
CREATE INDEX        ix_users_clinic_name ON core.users (clinic_id, name);
-- FK created_by
CREATE INDEX        ix_users_created_by  ON core.users (created_by) WHERE created_by IS NOT NULL;

CREATE TRIGGER trg_users_set_updated_at
  BEFORE UPDATE ON core.users
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_users_forbid_immutable
  BEFORE UPDATE ON core.users
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'clinic_id', 'role', 'created_at');

ALTER TABLE core.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.users FORCE  ROW LEVEL SECURITY;

CREATE POLICY users_sel ON core.users
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id());

CREATE POLICY users_ins ON core.users
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id());

CREATE POLICY users_upd ON core.users
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id())
  WITH CHECK (clinic_id = core.current_clinic_id());

CREATE POLICY users_backup_sel ON core.users
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE: usuário é desativado, não apagado
GRANT SELECT, INSERT, UPDATE ON core.users TO crm_app;
GRANT SELECT                 ON core.users TO crm_backup;
