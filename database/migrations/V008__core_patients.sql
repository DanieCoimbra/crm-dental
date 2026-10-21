-- =====================================================================
-- V008__core_patients.sql | Fase 1 / F02-patients
-- Objetivo : tabela core.patients (cadastro do paciente) com RLS por clínica e por papel
--            (DENTIST_ADMIN e RECEPTIONIST), CPF único por clínica, busca por nome (trigram e
--            prefixo), inativação lógica e grants por coluna (anonymized_at só pela F06).
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U008__core_patients.sql
-- Depende  : V002 (triggers genéricos), V004 (users, ux_users_id_clinic), V007 (funções de sessão),
--            bootstrap/20_extensions.sql (pg_trgm no schema extensions).
-- Observações:
--   * ix_patients_created_by indexa a FK composta de created_by (regra: índice em toda FK).
--   * Dados do paciente em texto claro (decisão P2); só o prontuário é cifrado (F03).
-- =====================================================================

CREATE TABLE core.patients (
  id                 uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id          uuid         NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  name               varchar(120) NOT NULL,
  cpf                varchar(11),
  birth_date         date         NOT NULL,
  sex                varchar(15)  NOT NULL DEFAULT 'NOT_INFORMED',
  profession         varchar(100),
  phone              varchar(20)  NOT NULL,
  email              varchar(254),
  address            varchar(255),
  insurance_name     varchar(100),
  insurance_number   varchar(40),
  acquaintance_name  varchar(120),
  acquaintance_phone varchar(20),
  active             boolean      NOT NULL DEFAULT true,
  deactivated_at     timestamptz,
  anonymized_at      timestamptz,
  created_by         uuid         NOT NULL,
  created_at         timestamptz  NOT NULL DEFAULT now(),
  updated_at         timestamptz  NOT NULL DEFAULT now(),
  version            bigint       NOT NULL DEFAULT 0,
  CONSTRAINT ck_patients_name        CHECK (char_length(btrim(name)) >= 2),
  CONSTRAINT ck_patients_cpf         CHECK (cpf ~ '^[0-9]{11}$'),
  CONSTRAINT ck_patients_birth       CHECK (birth_date >= DATE '1900-01-01'),
  CONSTRAINT ck_patients_sex         CHECK (sex IN ('FEMALE', 'MALE', 'OTHER', 'NOT_INFORMED')),
  CONSTRAINT ck_patients_phone       CHECK (phone ~ '^[0-9]{10,13}$'),
  CONSTRAINT ck_patients_email       CHECK (email = lower(email)),
  CONSTRAINT ck_patients_insurance   CHECK (insurance_number IS NULL OR insurance_name IS NOT NULL),
  CONSTRAINT ck_patients_acq_pair    CHECK ((acquaintance_name IS NULL) = (acquaintance_phone IS NULL)),
  CONSTRAINT ck_patients_acq_phone   CHECK (acquaintance_phone ~ '^[0-9]{10,13}$'),
  CONSTRAINT ck_patients_deactivated CHECK ((active AND deactivated_at IS NULL)
                                            OR (NOT active AND deactivated_at IS NOT NULL)),
  CONSTRAINT ck_patients_anonymized  CHECK (anonymized_at IS NULL OR NOT active),
  CONSTRAINT fk_patients_created_by  FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_patients_id_clinic   UNIQUE (id, clinic_id)
);

-- CPF único dentro da clínica (opcional); busca por igualdade
CREATE UNIQUE INDEX ux_patients_clinic_cpf ON core.patients (clinic_id, cpf) WHERE cpf IS NOT NULL;
-- Listagem paginada por keyset (name, id)
CREATE INDEX ix_patients_clinic_name ON core.patients (clinic_id, name, id);
-- Busca por trecho do nome (a partir de 3 caracteres); a RLS filtra a clínica
CREATE INDEX ix_patients_name_trgm ON core.patients USING gin (lower(name) extensions.gin_trgm_ops);
-- Busca por prefixo (1 a 2 caracteres)
CREATE INDEX ix_patients_name_prefix ON core.patients (clinic_id, lower(name) text_pattern_ops);

-- FK composta created_by (regra: índice em toda FK)
CREATE INDEX ix_patients_created_by ON core.patients (created_by, clinic_id);

CREATE TRIGGER trg_patients_set_updated_at
  BEFORE UPDATE ON core.patients
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_patients_forbid_immutable
  BEFORE UPDATE ON core.patients
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'clinic_id', 'created_by', 'created_at');

ALTER TABLE core.patients ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.patients FORCE  ROW LEVEL SECURITY;

-- Os dois papéis são escritos como OR de igualdades (e não IN): sobre varchar, o IN é reinterpretado pelo
-- pg_dump/pg_restore como outra árvore de expressão, e o guarda de catálogo da restauração (tests/backup_restore.sh,
-- scripts/restore_check.sh) acusaria diferença no modo full. A semântica é idêntica.
-- Desempenho: as funções de sessão são chamadas como (SELECT ...): o PostgreSQL as avalia UMA vez por consulta (InitPlan)
-- e não uma vez por linha (funções com SET search_path não são embutidas e custam ~1,5 us por linha).
CREATE POLICY patients_sel ON core.patients
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));

CREATE POLICY patients_ins ON core.patients
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST')
              AND created_by = core.current_user_id());

CREATE POLICY patients_upd ON core.patients
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'))
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));

CREATE POLICY patients_backup_sel ON core.patients
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE: paciente é inativado, nunca apagado. INSERT e UPDATE por coluna: anonymized_at fica
-- de fora (só a função de anonimização da F06 o grava); as colunas imutáveis ficam fora do UPDATE.
GRANT SELECT ON core.patients TO crm_app, crm_backup;
GRANT INSERT (id, clinic_id, name, cpf, birth_date, sex, profession, phone, email, address,
              insurance_name, insurance_number, acquaintance_name, acquaintance_phone,
              active, deactivated_at, created_by, created_at, updated_at, version)
  ON core.patients TO crm_app;
GRANT UPDATE (name, cpf, birth_date, sex, profession, phone, email, address,
              insurance_name, insurance_number, acquaintance_name, acquaintance_phone,
              active, deactivated_at, updated_at, version)
  ON core.patients TO crm_app;
