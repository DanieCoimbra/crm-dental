-- =====================================================================
-- V016__core_business_hours.sql | Fase 2 / F03-business-hours
-- Objetivo : horário comercial da clínica (core.business_hours): UMA linha por clínica e dia da semana, com expediente
--            e intervalo de almoço opcional. Configuração: leitura para os dois papéis, escrita só do DENTIST_ADMIN.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U016__core_business_hours.sql
-- Depende  : V004 (users), V007 (funções de sessão), V002 (triggers genéricos), V003 (clinics).
-- Erros    : 23514 CHECK (expediente, almoço, weekday) ou coluna imutável; 23505 dia já configurado; 42501 papel ou autoria.
-- Observações:
--   * Uma linha por dia (e não uma por faixa): a ordem entre expediente e almoço vira CHECK de uma linha só, atômico.
--   * Dia SEM linha = não configurado (o backend o trata como fechado); dia fechado de propósito = is_open = false.
--   * Sem DELETE: a tabela tem no máximo 7 linhas por clínica e o dia fechado é is_open = false.
--   * Horários são LOCAIS da clínica (clinics.timezone); a conversão para timestamptz é do backend.
--   * O banco NÃO impede agendamento fora do horário comercial (decisão A8): o backend avisa ou bloqueia.
--   * created_by (FK composta) não tem índice de propósito: users nunca é apagado.
-- =====================================================================

CREATE TABLE core.business_hours (
  id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id       uuid        NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  weekday         smallint    NOT NULL,
  is_open         boolean     NOT NULL,
  opens_at        time(0),
  closes_at       time(0),
  lunch_starts_at time(0),
  lunch_ends_at   time(0),
  created_by      uuid        NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  version         bigint      NOT NULL DEFAULT 0,
  -- ISO: 1 = segunda ... 7 = domingo (igual a DayOfWeek.getValue() do Java)
  CONSTRAINT ck_bh_weekday CHECK (weekday BETWEEN 1 AND 7),
  CONSTRAINT ck_bh_open    CHECK ((is_open AND opens_at IS NOT NULL AND closes_at IS NOT NULL AND opens_at < closes_at)
                               OR (NOT is_open AND opens_at IS NULL AND closes_at IS NULL
                                   AND lunch_starts_at IS NULL AND lunch_ends_at IS NULL)),
  CONSTRAINT ck_bh_lunch   CHECK ((lunch_starts_at IS NULL AND lunch_ends_at IS NULL)
                               OR (lunch_starts_at IS NOT NULL AND lunch_ends_at IS NOT NULL
                                   AND opens_at < lunch_starts_at AND lunch_starts_at < lunch_ends_at
                                   AND lunch_ends_at < closes_at)),
  -- também é o índice de leitura da semana e da FK de clinic_id
  CONSTRAINT ux_bh_clinic_weekday UNIQUE (clinic_id, weekday),
  CONSTRAINT fk_bh_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT
);

CREATE TRIGGER trg_bh_set_updated_at
  BEFORE UPDATE ON core.business_hours
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_bh_forbid_immutable
  BEFORE UPDATE ON core.business_hours
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'clinic_id', 'weekday', 'created_by', 'created_at');

ALTER TABLE core.business_hours ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.business_hours FORCE  ROW LEVEL SECURITY;

CREATE POLICY bh_sel ON core.business_hours
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));

CREATE POLICY bh_ins ON core.business_hours
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN'
              AND created_by = core.current_user_id());

CREATE POLICY bh_upd ON core.business_hours
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY bh_backup_sel ON core.business_hours
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE (S3)
GRANT SELECT, INSERT, UPDATE ON core.business_hours TO crm_app;
GRANT SELECT                 ON core.business_hours TO crm_backup;
