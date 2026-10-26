-- =====================================================================
-- V026__core_installments.sql | Fase 3 / F04-installments
-- Objetivo : parcelas do orçamento aprovado (core.installments). Só nascem de orçamento APPROVED, OPEN; a soma das parcelas não
--            canceladas é igual ao total do orçamento ao fim da transação (constraint trigger adiável); o status é coerente com os
--            pagamentos (OPEN/PAID) e CANCELED só com líquido pago zero. Visão e escrita só do DENTIST_ADMIN. Sem DELETE.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U026__core_installments.sql (reverter antes U027)
-- Depende  : V024 (budgets e seu UNIQUE (id, patient_id, clinic_id)), V008 (patients), V004 (users), V002, V007.
-- Erros    : CR009 regra da parcela (orçamento não APPROVED, criada fora de OPEN, status incoerente com o líquido pago, parcela
--            terminal ou paga alterada, cancelamento com saldo pago); CR011 soma das parcelas diferente do total (no COMMIT);
--            CR003 paciente anonimizado; 23505 número repetido entre as ativas; 23514 CHECK ou coluna imutável; 23503 FK; 42501.
-- Observações:
--   * core.installment_net_paid lê core.payments, criada na V027: a função é PL/pgSQL (corpo não validado na criação) e só é chamada
--     quando o status de uma parcela muda. Os testes de status e cancelamento rodam sobre V001-V027.
--   * installment_net_paid recebe EXECUTE do crm_app (é chamada dentro dos triggers, com o papel do chamador); roda como invoker,
--     então só enxerga o que a RLS do chamador permite.
--   * O número da parcela é único só entre as NÃO canceladas (índice único parcial): refazer o plano reaproveita 1, 2, 3...
--   * A constraint trigger só falha no COMMIT; nos testes, SET CONSTRAINTS ALL IMMEDIATE força a checagem.
--   * UNIQUE (id, clinic_id): alvo da FK de payments (exceção ao S1 da Fase 2: volume baixo).
--   * created_by (FK composta) não tem índice de propósito: users nunca é apagado.
-- =====================================================================

CREATE TABLE core.installments (
  id         uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id  uuid          NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  budget_id  uuid          NOT NULL,
  patient_id uuid          NOT NULL,
  number     smallint      NOT NULL,
  amount     numeric(12,2) NOT NULL,
  due_date   date          NOT NULL,
  status     varchar(10)   NOT NULL DEFAULT 'OPEN',
  created_by uuid          NOT NULL,
  created_at timestamptz   NOT NULL DEFAULT now(),
  updated_at timestamptz   NOT NULL DEFAULT now(),
  version    bigint        NOT NULL DEFAULT 0,
  CONSTRAINT ck_inst_number CHECK (number BETWEEN 1 AND 60),
  CONSTRAINT ck_inst_amount CHECK (amount BETWEEN 0.01 AND 999999.99),
  CONSTRAINT ck_inst_due    CHECK (due_date >= DATE '2000-01-01'),
  CONSTRAINT ck_inst_status CHECK (status IN ('OPEN', 'PAID', 'CANCELED')),
  CONSTRAINT fk_inst_budget     FOREIGN KEY (budget_id, patient_id, clinic_id)
    REFERENCES core.budgets (id, patient_id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_inst_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_inst_id_clinic UNIQUE (id, clinic_id)
);

-- No máximo 60 parcelas ativas por orçamento (número 1..60 + unicidade entre as não canceladas)
CREATE UNIQUE INDEX ux_inst_budget_number ON core.installments (budget_id, number) WHERE status <> 'CANCELED';
-- Parcelas do orçamento (todas, inclusive canceladas) e índice da FK do orçamento
CREATE INDEX ix_inst_budget   ON core.installments (budget_id, number, id);
-- Ficha financeira do paciente, índice da FK e CR006 (anonimização)
CREATE INDEX ix_inst_patient  ON core.installments (clinic_id, patient_id, due_date, id);
-- Contas a receber e inadimplência (o backend informa "hoje"). Predicado com = (e não IN): ver a nota da V015.
CREATE INDEX ix_inst_open_due ON core.installments (clinic_id, due_date, id) WHERE status = 'OPEN';

-- Líquido pago da parcela = Σ PAYMENT − Σ REVERSAL. PL/pgSQL de propósito (ver a observação do cabeçalho).
CREATE FUNCTION core.installment_net_paid(p_installment uuid) RETURNS numeric
  LANGUAGE plpgsql STABLE
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  RETURN COALESCE((SELECT sum(CASE p.kind WHEN 'PAYMENT' THEN p.amount ELSE -p.amount END)
                     FROM core.payments p WHERE p.installment_id = p_installment), 0);
END;
$$;

-- Geração e transições da parcela
CREATE FUNCTION core.guard_installment() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_status     varchar(10);
  v_anonymized timestamptz;
  v_net        numeric;
BEGIN
  IF TG_OP = 'INSERT' THEN
    -- orçamento invisível (outro papel, outra clínica) ou inexistente: RLS e FK respondem (S16)
    SELECT b.status INTO v_status
      FROM core.budgets b
     WHERE b.id = NEW.budget_id AND b.clinic_id = NEW.clinic_id
       FOR KEY SHARE;
    IF NOT FOUND THEN
      RETURN NEW;
    END IF;
    IF v_status <> 'APPROVED' THEN
      RAISE EXCEPTION 'parcelas so de orcamento aprovado' USING ERRCODE = 'CR009';
    END IF;
    IF NEW.status <> 'OPEN' THEN
      RAISE EXCEPTION 'parcela nasce em aberto' USING ERRCODE = 'CR009';
    END IF;
    -- FOR KEY SHARE: espera uma anonimização em curso e relê o paciente já anonimizado (padrão da V013)
    SELECT p.anonymized_at INTO v_anonymized
      FROM core.patients p
     WHERE p.id = NEW.patient_id AND p.clinic_id = NEW.clinic_id
       FOR KEY SHARE;
    IF v_anonymized IS NOT NULL THEN
      RAISE EXCEPTION 'paciente anonimizado' USING ERRCODE = 'CR003';
    END IF;
    RETURN NEW;
  END IF;

  -- UPDATE: o JPA reenvia todas as colunas, então só se confere o que mudou
  IF NEW.due_date IS DISTINCT FROM OLD.due_date AND OLD.status <> 'OPEN' THEN
    RAISE EXCEPTION 'vencimento so muda enquanto a parcela esta em aberto' USING ERRCODE = 'CR009';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF OLD.status = 'CANCELED' THEN
      RAISE EXCEPTION 'parcela cancelada e terminal' USING ERRCODE = 'CR009';
    END IF;
    v_net := core.installment_net_paid(OLD.id);
    IF NEW.status = 'CANCELED' THEN
      IF OLD.status <> 'OPEN' OR v_net <> 0 THEN
        RAISE EXCEPTION 'parcela com saldo pago nao se cancela' USING ERRCODE = 'CR009';
      END IF;
    ELSIF NEW.status = 'PAID' THEN
      IF v_net < OLD.amount THEN
        RAISE EXCEPTION 'parcela so e paga quando o liquido cobre o valor' USING ERRCODE = 'CR009';
      END IF;
    ELSIF NEW.status = 'OPEN' THEN
      IF v_net >= OLD.amount THEN
        RAISE EXCEPTION 'parcela quitada nao volta a aberta sem estorno' USING ERRCODE = 'CR009';
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

-- Σ das parcelas ativas = total do orçamento (checado no COMMIT). Sem parcela ativa: passa.
CREATE FUNCTION core.check_installments_sum() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_count integer;
  v_sum   numeric;
  v_total numeric;
BEGIN
  SELECT count(*) FILTER (WHERE i.status <> 'CANCELED'),
         COALESCE(sum(i.amount) FILTER (WHERE i.status <> 'CANCELED'), 0)
    INTO v_count, v_sum
    FROM core.installments i
   WHERE i.budget_id = NEW.budget_id AND i.clinic_id = NEW.clinic_id;
  IF v_count = 0 THEN
    RETURN NULL;
  END IF;
  SELECT b.total_amount INTO v_total FROM core.budgets b WHERE b.id = NEW.budget_id AND b.clinic_id = NEW.clinic_id;
  IF v_total IS NULL OR v_sum <> v_total THEN
    RAISE EXCEPTION 'as parcelas ativas nao somam o total do orcamento' USING ERRCODE = 'CR011';
  END IF;
  RETURN NULL;
END;
$$;

REVOKE EXECUTE ON FUNCTION core.installment_net_paid(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.guard_installment()        FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.check_installments_sum()   FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION core.installment_net_paid(uuid) TO crm_app;

CREATE TRIGGER trg_inst_set_updated_at
  BEFORE UPDATE ON core.installments
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_inst_forbid_immutable
  BEFORE UPDATE ON core.installments
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns(
    'id', 'clinic_id', 'budget_id', 'patient_id', 'number', 'amount', 'created_by', 'created_at');

CREATE TRIGGER trg_inst_guard
  BEFORE INSERT OR UPDATE ON core.installments
  FOR EACH ROW EXECUTE FUNCTION core.guard_installment();

CREATE CONSTRAINT TRIGGER ctrg_inst_sum_matches_total
  AFTER INSERT OR UPDATE OF amount, status ON core.installments
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION core.check_installments_sum();

ALTER TABLE core.installments ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.installments FORCE  ROW LEVEL SECURITY;

-- Dado financeiro: só o dentista lê e grava (decisão C2). A recepção não tem política: enxerga 0 linhas.
CREATE POLICY inst_sel ON core.installments
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY inst_ins ON core.installments
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND created_by = core.current_user_id()
              AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY inst_upd ON core.installments
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY inst_backup_sel ON core.installments
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE: o histórico fica no status e na auditoria
GRANT SELECT, INSERT, UPDATE ON core.installments TO crm_app;
GRANT SELECT                 ON core.installments TO crm_backup;
