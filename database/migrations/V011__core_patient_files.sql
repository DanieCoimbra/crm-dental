-- =====================================================================
-- V011__core_patient_files.sql | Fase 1 / F04-patient-files
-- Objetivo : metadados dos exames (radiografias, fotos) guardados no Supabase Storage (bucket
--            privado, acessado só pelo backend). RLS assimétrica: INSERT para DENTIST_ADMIN e
--            RECEPTIONIST; SELECT e UPDATE só para DENTIST_ADMIN. Sem DELETE (remoção lógica).
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U011__core_patient_files.sql
-- Depende  : V008 (patients), V004 (users), V007 (funções de sessão).
-- Backend  : o INSERT da recepção não pode usar RETURNING nem recarregar a linha (a política de
--            SELECT a nega).
-- Observação: o índice de created_by (FK composta) é omitido de propósito (users não é apagado).
-- =====================================================================

CREATE TABLE core.patient_files (
  id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id         uuid         NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  patient_id        uuid         NOT NULL,
  kind              varchar(20)  NOT NULL,
  storage_key       varchar(120) NOT NULL,
  original_filename varchar(255) NOT NULL,
  content_type      varchar(50)  NOT NULL,
  size_bytes        bigint       NOT NULL,
  deleted_at        timestamptz,
  created_by        uuid         NOT NULL,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  updated_at        timestamptz  NOT NULL DEFAULT now(),
  version           bigint       NOT NULL DEFAULT 0,
  CONSTRAINT ck_files_kind CHECK (kind IN ('RADIOGRAPH', 'INTRAORAL_PHOTO', 'OTHER')),
  CONSTRAINT ck_files_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')),
  CONSTRAINT ck_files_size CHECK (size_bytes BETWEEN 1 AND 10485760),
  CONSTRAINT ck_files_key  CHECK (storage_key ~ ('^' || clinic_id::text || '/' || patient_id::text || '/[0-9a-f-]{36}$')),
  CONSTRAINT fk_files_patient FOREIGN KEY (patient_id, clinic_id)
    REFERENCES core.patients (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_files_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_files_storage_key UNIQUE (storage_key)
);

-- Lista de exames ativos do paciente (keyset por created_at, id); serve também à FK composta
CREATE INDEX ix_files_patient ON core.patient_files (clinic_id, patient_id, created_at DESC, id DESC)
  WHERE deleted_at IS NULL;

CREATE TRIGGER trg_files_set_updated_at
  BEFORE UPDATE ON core.patient_files
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

-- original_filename NÃO é imutável: a anonimização (F06) o substitui
CREATE TRIGGER trg_files_forbid_immutable
  BEFORE UPDATE ON core.patient_files
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns(
    'id', 'clinic_id', 'patient_id', 'storage_key', 'content_type', 'size_bytes', 'created_by', 'created_at');

ALTER TABLE core.patient_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.patient_files FORCE  ROW LEVEL SECURITY;

-- Desempenho: as funções de sessão são chamadas como (SELECT ...): o PostgreSQL as avalia UMA vez por consulta (InitPlan)
-- e não uma vez por linha (funções com SET search_path não são embutidas e custam ~1,5 us por linha).
CREATE POLICY files_sel ON core.patient_files
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

-- A recepção anexa exames, mas não os enxerga nem os altera depois.
-- Os dois papéis são escritos como OR de igualdades (e não IN): sobre varchar, o IN é reinterpretado pelo
-- pg_dump/pg_restore como outra árvore de expressão, e o guarda de catálogo da restauração (tests/backup_restore.sh,
-- scripts/restore_check.sh) acusaria diferença no modo full. A semântica é idêntica.
CREATE POLICY files_ins ON core.patient_files
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST')
              AND created_by = core.current_user_id());

CREATE POLICY files_upd ON core.patient_files
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY files_backup_sel ON core.patient_files
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE: remoção lógica por deleted_at
GRANT SELECT, INSERT, UPDATE ON core.patient_files TO crm_app;
GRANT SELECT                 ON core.patient_files TO crm_backup;
