-- =====================================================================
-- V003__core_clinics.sql | F03-core-tables
-- Objetivo : tabela core.clinics (o tenant), com RLS por id, triggers e grants mínimos.
-- Executar : Flyway, como crm_owner.
-- Reverter : database/rollback/U003__core_clinics.sql
-- Depende  : V001 (privilégios), V002 (core.current_clinic_id e funções de trigger).
-- =====================================================================

CREATE TABLE core.clinics (
  id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  name        varchar(150) NOT NULL,
  document    varchar(20),
  phone       varchar(20),
  email       varchar(254),
  address     varchar(255),
  created_at  timestamptz  NOT NULL DEFAULT now(),
  updated_at  timestamptz  NOT NULL DEFAULT now(),
  version     bigint       NOT NULL DEFAULT 0,
  CONSTRAINT ck_clinics_name CHECK (char_length(name) >= 2)
);

CREATE TRIGGER trg_clinics_set_updated_at
  BEFORE UPDATE ON core.clinics
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_clinics_forbid_immutable
  BEFORE UPDATE ON core.clinics
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'created_at');

-- RLS: o tenant é a própria clínica (id = tenant). Sem política de DELETE: negado.
ALTER TABLE core.clinics ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.clinics FORCE  ROW LEVEL SECURITY;

CREATE POLICY clinics_sel ON core.clinics
  FOR SELECT TO crm_app
  USING (id = core.current_clinic_id());

CREATE POLICY clinics_ins ON core.clinics
  FOR INSERT TO crm_app
  WITH CHECK (id = core.current_clinic_id());

CREATE POLICY clinics_upd ON core.clinics
  FOR UPDATE TO crm_app
  USING (id = core.current_clinic_id())
  WITH CHECK (id = core.current_clinic_id());

-- Backup: leitura de todas as linhas por política nomeada (sem BYPASSRLS)
CREATE POLICY clinics_backup_sel ON core.clinics
  FOR SELECT TO crm_backup
  USING (true);

GRANT SELECT, INSERT, UPDATE ON core.clinics TO crm_app;
GRANT SELECT                 ON core.clinics TO crm_backup;
