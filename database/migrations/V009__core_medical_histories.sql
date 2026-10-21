-- =====================================================================
-- V009__core_medical_histories.sql | Fase 1 / F03-clinical-records
-- Objetivo : histórico médico do paciente (no máximo 1 por paciente), conteúdo CIFRADO pela
--            aplicação (AES-256-GCM) e opaco para o banco; RLS por clínica E por DENTIST_ADMIN.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U009__core_medical_histories.sql
-- Depende  : V008 (patients, ux_patients_id_clinic), V004 (users), V007 (funções de sessão).
-- Cifra    : conteúdo = Base64( versão_da_chave[1] | IV[12] | cifra + tag[16] ). Pior caso de
--            4 bytes UTF-8 por caractere para 10.000 caracteres: 40.029 bytes -> 53.372 em Base64
--            -> varchar(54000). O CHECK rejeita texto claro (espaços/pontuação) e conteúdo curto.
-- Observação: o índice de created_by (FK composta) é omitido de propósito (users não é apagado).
-- =====================================================================

CREATE TABLE core.medical_histories (
  id         uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id  uuid          NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  patient_id uuid          NOT NULL,
  content    varchar(54000) NOT NULL,
  created_by uuid          NOT NULL,
  created_at timestamptz   NOT NULL DEFAULT now(),
  updated_at timestamptz   NOT NULL DEFAULT now(),
  version    bigint        NOT NULL DEFAULT 0,
  CONSTRAINT ck_histories_content CHECK (char_length(content) >= 40 AND content ~ '^[A-Za-z0-9+/]+={0,2}$'),
  CONSTRAINT fk_histories_patient FOREIGN KEY (patient_id, clinic_id)
    REFERENCES core.patients (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_histories_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_histories_patient UNIQUE (patient_id)
);

-- Suporte à RLS e a consultas por clínica
CREATE INDEX ix_histories_clinic ON core.medical_histories (clinic_id);

CREATE TRIGGER trg_histories_set_updated_at
  BEFORE UPDATE ON core.medical_histories
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_histories_forbid_immutable
  BEFORE UPDATE ON core.medical_histories
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'clinic_id', 'patient_id', 'created_by', 'created_at');

ALTER TABLE core.medical_histories ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.medical_histories FORCE  ROW LEVEL SECURITY;

-- Desempenho: as funções de sessão são chamadas como (SELECT ...): o PostgreSQL as avalia UMA vez por consulta (InitPlan)
-- e não uma vez por linha (funções com SET search_path não são embutidas e custam ~1,5 us por linha).
CREATE POLICY histories_sel ON core.medical_histories
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY histories_ins ON core.medical_histories
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN'
              AND created_by = core.current_user_id());

CREATE POLICY histories_upd ON core.medical_histories
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY histories_backup_sel ON core.medical_histories
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE: o prontuário só é removido pela anonimização (F06, crm_definer)
GRANT SELECT, INSERT, UPDATE ON core.medical_histories TO crm_app;
GRANT SELECT                 ON core.medical_histories TO crm_backup;
