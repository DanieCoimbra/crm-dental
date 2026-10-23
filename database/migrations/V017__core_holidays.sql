-- =====================================================================
-- V017__core_holidays.sql | Fase 2 / F04-holidays
-- Objetivo : feriados. core.holidays é GLOBAL (nacionais, sem clinic_id, legível por qualquer sessão da aplicação,
--            escrito só por migrations do crm_owner); core.clinic_holidays é por clínica (leitura para os dois papéis,
--            escrita e remoção só do DENTIST_ADMIN). O seed nacional está na V018.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U017__core_holidays.sql
-- Depende  : V004 (users), V007 (funções de sessão), V002 (triggers genéricos), V003 (clinics).
-- Erros    : 23505 data já cadastrada na clínica; 23514 data anterior a 2000, nome curto ou coluna imutável;
--            42501 papel, autoria ou escrita em holidays pela aplicação.
-- Observações:
--   * Feriado é aviso/bloqueio decidido pelo backend; o banco aceita agendar em feriado (decisão A9).
--   * core.holidays tem FORCE: o próprio dono também obedece à RLS, então as migrations de seed escrevem por políticas
--     dedicadas ao crm_owner (uma por operação). A aplicação (crm_app) só lê.
--   * clinic_holidays é a ÚNICA tabela da aplicação com DELETE físico: configuração sem dado pessoal e sem histórico a
--     preservar (nenhuma tabela aponta para o feriado).
--   * created_by (FK composta) não tem índice de propósito: users nunca é apagado.
-- =====================================================================

-- ---------------------------------------------------------------------
-- core.holidays (global)
-- ---------------------------------------------------------------------
CREATE TABLE core.holidays (
  holiday_date date        PRIMARY KEY,
  name         varchar(80) NOT NULL,
  CONSTRAINT ck_holidays_name CHECK (char_length(btrim(name)) >= 2)
);

ALTER TABLE core.holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.holidays FORCE  ROW LEVEL SECURITY;

-- Dado público e igual para todas as clínicas: USING (true) é intencional (o catalog_rls_pattern.sql trata esta tabela)
CREATE POLICY hol_sel ON core.holidays
  FOR SELECT TO crm_app
  USING (true);

CREATE POLICY hol_backup_sel ON core.holidays
  FOR SELECT TO crm_backup
  USING (true);

-- Só para migrations/seed (o crm_owner não é usado pela aplicação)
CREATE POLICY hol_owner_sel ON core.holidays FOR SELECT TO crm_owner USING (true);
CREATE POLICY hol_owner_ins ON core.holidays FOR INSERT TO crm_owner WITH CHECK (true);
CREATE POLICY hol_owner_upd ON core.holidays FOR UPDATE TO crm_owner USING (true) WITH CHECK (true);
CREATE POLICY hol_owner_del ON core.holidays FOR DELETE TO crm_owner USING (true);

GRANT SELECT ON core.holidays TO crm_app, crm_backup;

-- ---------------------------------------------------------------------
-- core.clinic_holidays (por clínica)
-- ---------------------------------------------------------------------
CREATE TABLE core.clinic_holidays (
  id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id    uuid        NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  holiday_date date        NOT NULL,
  name         varchar(80) NOT NULL,
  created_by   uuid        NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  version      bigint      NOT NULL DEFAULT 0,
  CONSTRAINT ck_chol_date CHECK (holiday_date >= DATE '2000-01-01'),
  CONSTRAINT ck_chol_name CHECK (char_length(btrim(name)) >= 2),
  -- a clínica pode repetir uma data nacional (não precisa saber que já existe); também indexa clinic_id e a FK
  CONSTRAINT ux_clinic_holidays_date UNIQUE (clinic_id, holiday_date),
  CONSTRAINT fk_chol_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT
);

CREATE TRIGGER trg_chol_set_updated_at
  BEFORE UPDATE ON core.clinic_holidays
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_chol_forbid_immutable
  BEFORE UPDATE ON core.clinic_holidays
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'clinic_id', 'created_by', 'created_at');

ALTER TABLE core.clinic_holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.clinic_holidays FORCE  ROW LEVEL SECURITY;

CREATE POLICY chol_sel ON core.clinic_holidays
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));

CREATE POLICY chol_ins ON core.clinic_holidays
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN'
              AND created_by = core.current_user_id());

CREATE POLICY chol_upd ON core.clinic_holidays
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY chol_del ON core.clinic_holidays
  FOR DELETE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY chol_backup_sel ON core.clinic_holidays
  FOR SELECT TO crm_backup
  USING (true);

GRANT SELECT, INSERT, UPDATE, DELETE ON core.clinic_holidays TO crm_app;
GRANT SELECT                         ON core.clinic_holidays TO crm_backup;
