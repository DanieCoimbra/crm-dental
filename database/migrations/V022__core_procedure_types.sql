-- =====================================================================
-- V022__core_procedure_types.sql | Fase 3 / F01-procedure-types
-- Objetivo : catálogo de procedimentos da clínica (nome, preço-base, duração padrão e cor na agenda). Configuração:
--            sem DELETE (inativar é active = false), sem auditoria. Só DENTIST_ADMIN grava; a recepção só lê.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U022__core_procedure_types.sql
-- Depende  : V003 (clinics), V004 (users), V002 (triggers genéricos), V007 (funções de sessão).
-- Erros    : 23514 CHECK ou coluna imutável; 23505 nome ativo repetido (sem diferenciar caixa); 42501 papel ou autoria.
-- Observações:
--   * UNIQUE (id, clinic_id) é o alvo das FKs compostas de appointments (V023) e de budget_items (V024).
--   * created_by (FK composta) não tem índice de propósito: users nunca é apagado.
--   * Mudar base_price NÃO altera orçamentos já criados: o item guarda o preço copiado (V024).
-- =====================================================================

CREATE TABLE core.procedure_types (
  id                       uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id                uuid         NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  name                     varchar(80)  NOT NULL,
  base_price               numeric(12,2) NOT NULL,
  default_duration_minutes smallint     NOT NULL,
  color                    varchar(7)   NOT NULL,
  active                   boolean      NOT NULL DEFAULT true,
  created_by               uuid         NOT NULL,
  created_at               timestamptz  NOT NULL DEFAULT now(),
  updated_at               timestamptz  NOT NULL DEFAULT now(),
  version                  bigint       NOT NULL DEFAULT 0,
  CONSTRAINT ck_ptype_name     CHECK (char_length(btrim(name)) >= 2),
  CONSTRAINT ck_ptype_price    CHECK (base_price BETWEEN 0 AND 999999.99),
  CONSTRAINT ck_ptype_duration CHECK (default_duration_minutes BETWEEN 5 AND 720 AND default_duration_minutes % 5 = 0),
  CONSTRAINT ck_ptype_color    CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
  CONSTRAINT fk_ptype_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_ptype_id_clinic UNIQUE (id, clinic_id)
);

-- Nome único entre os ativos, sem diferenciar caixa; inativar libera o nome. Também serve à lista da clínica.
CREATE UNIQUE INDEX ux_ptype_clinic_name ON core.procedure_types (clinic_id, lower(name)) WHERE active;

CREATE TRIGGER trg_ptype_set_updated_at
  BEFORE UPDATE ON core.procedure_types
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_ptype_forbid_immutable
  BEFORE UPDATE ON core.procedure_types
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'clinic_id', 'created_by', 'created_at');

ALTER TABLE core.procedure_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.procedure_types FORCE  ROW LEVEL SECURITY;

-- Papéis como OR de igualdades (e não IN) e funções de sessão como (SELECT ...): ver a nota da V008/V015.
CREATE POLICY ptype_sel ON core.procedure_types
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));

CREATE POLICY ptype_ins ON core.procedure_types
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND created_by = core.current_user_id()
              AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY ptype_upd ON core.procedure_types
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY ptype_backup_sel ON core.procedure_types
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE: inativar é active = false
GRANT SELECT, INSERT, UPDATE ON core.procedure_types TO crm_app;
GRANT SELECT                 ON core.procedure_types TO crm_backup;
