-- =====================================================================
-- V010__core_clinical_notes.sql | Fase 1 / F03-clinical-records
-- Objetivo : notas clínicas por atendimento, conteúdo CIFRADO pela aplicação e opaco para o banco;
--            RLS por clínica E por DENTIST_ADMIN; editáveis, nunca apagadas por crm_app.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U010__core_clinical_notes.sql
-- Depende  : V008 (patients), V004 (users), V007 (funções de sessão).
-- Cifra    : mesmo formato da V009. Pior caso de 4 bytes UTF-8 por caractere para 5.000 caracteres:
--            20.029 bytes -> 26.708 em Base64 -> varchar(27000).
-- Observação: o vínculo com agendamento (appointment_id) entra na Fase 2, nulo e aditivo.
--            O índice de created_by (FK composta) é omitido de propósito (users não é apagado).
-- =====================================================================

CREATE TABLE core.clinical_notes (
  id         uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id  uuid          NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  patient_id uuid          NOT NULL,
  noted_at   timestamptz   NOT NULL DEFAULT now(),
  content    varchar(27000) NOT NULL,
  created_by uuid          NOT NULL,
  created_at timestamptz   NOT NULL DEFAULT now(),
  updated_at timestamptz   NOT NULL DEFAULT now(),
  version    bigint        NOT NULL DEFAULT 0,
  CONSTRAINT ck_notes_content CHECK (char_length(content) >= 40 AND content ~ '^[A-Za-z0-9+/]+={0,2}$'),
  CONSTRAINT fk_notes_patient FOREIGN KEY (patient_id, clinic_id)
    REFERENCES core.patients (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_notes_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT
);

-- Linha do tempo paginada por keyset (noted_at, id); também serve de índice da FK composta
CREATE INDEX ix_notes_timeline ON core.clinical_notes (clinic_id, patient_id, noted_at DESC, id DESC);

CREATE TRIGGER trg_notes_set_updated_at
  BEFORE UPDATE ON core.clinical_notes
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_notes_forbid_immutable
  BEFORE UPDATE ON core.clinical_notes
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'clinic_id', 'patient_id', 'created_by', 'created_at');

ALTER TABLE core.clinical_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.clinical_notes FORCE  ROW LEVEL SECURITY;

-- Desempenho: as funções de sessão são chamadas como (SELECT ...): o PostgreSQL as avalia UMA vez por consulta (InitPlan)
-- e não uma vez por linha (funções com SET search_path não são embutidas e custam ~1,5 us por linha).
CREATE POLICY notes_sel ON core.clinical_notes
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY notes_ins ON core.clinical_notes
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN'
              AND created_by = core.current_user_id());

CREATE POLICY notes_upd ON core.clinical_notes
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY notes_backup_sel ON core.clinical_notes
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE: a nota só é removida pela anonimização (F06, crm_definer)
GRANT SELECT, INSERT, UPDATE ON core.clinical_notes TO crm_app;
GRANT SELECT                 ON core.clinical_notes TO crm_backup;
