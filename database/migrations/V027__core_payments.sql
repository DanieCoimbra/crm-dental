-- =====================================================================
-- V027__core_payments.sql | Fase 3 / F05-payments
-- Objetivo : pagamentos e estornos (core.payments), APPEND-ONLY: crm_app só tem SELECT e INSERT; um trigger nega UPDATE, DELETE e
--            TRUNCATE a qualquer papel (inclusive o dono). Pagamento parcial permitido; estorno é nova linha REVERSAL (mesma parcela,
--            mesmo valor, um por pagamento). O guard trava a parcela (FOR NO KEY UPDATE) e confere o saldo; o AFTER INSERT mantém
--            installments.status (OPEN/PAID) e incrementa version. Sem texto livre. Só o DENTIST_ADMIN lê e grava.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U027__core_payments.sql
-- Depende  : V026 (installments, installment_net_paid, guard_installment), V008 (patients), V004 (users), V007.
-- Erros    : CR010 pagamento excede o saldo, parcela cancelada, estorno com valor ou alvo inválido; CR003 paciente anonimizado;
--            23505 estorno repetido (ux_pay_reverses); 23503 FK (parcela ou pagamento estornado de outra clínica ou outra parcela);
--            23514 CHECK; 42501 papel, autoria ou tentativa de UPDATE/DELETE/TRUNCATE.
-- Observações:
--   * Paciente INATIVO ainda paga (a dívida continua); anonimizado não (CR003). Pagar não passa por guard de paciente inativo.
--   * O guard devolve NEW quando a parcela não é visível (S16): a recepção recebe 42501 da RLS, não um erro de FK.
--   * paid_on é a data civil do recebimento no fuso da clínica, calculada pelo backend; o banco só impede datas anteriores a 2000.
--   * UNIQUE (id, installment_id, clinic_id): alvo da FK do estorno (a mesma parcela). created_by sem índice (users não é apagado).
--   * O pg_restore carrega por COPY (não dispara UPDATE/DELETE); o TRUNCATE só é bloqueado fora de DDL de quem desliga o trigger.
-- =====================================================================

CREATE TABLE core.payments (
  id                  uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id           uuid          NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  installment_id      uuid          NOT NULL,
  kind                varchar(10)   NOT NULL,
  amount              numeric(12,2) NOT NULL,
  method              varchar(15)   NOT NULL,
  paid_on             date          NOT NULL,
  reverses_payment_id uuid,
  created_by          uuid          NOT NULL,
  created_at          timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT ck_pay_kind     CHECK (kind IN ('PAYMENT', 'REVERSAL')),
  CONSTRAINT ck_pay_amount   CHECK (amount BETWEEN 0.01 AND 999999.99),
  CONSTRAINT ck_pay_method   CHECK (method IN ('CASH', 'PIX', 'DEBIT_CARD', 'CREDIT_CARD', 'BANK_TRANSFER', 'OTHER')),
  CONSTRAINT ck_pay_date     CHECK (paid_on >= DATE '2000-01-01'),
  CONSTRAINT ck_pay_reversal CHECK ((kind = 'PAYMENT' AND reverses_payment_id IS NULL)
                                 OR (kind = 'REVERSAL' AND reverses_payment_id IS NOT NULL)),
  CONSTRAINT fk_pay_installment FOREIGN KEY (installment_id, clinic_id)
    REFERENCES core.installments (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_pay_reverses    FOREIGN KEY (reverses_payment_id, installment_id, clinic_id)
    REFERENCES core.payments (id, installment_id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_pay_created_by  FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_pay_id_inst_clinic UNIQUE (id, installment_id, clinic_id),
  -- um estorno por pagamento (NULL não colide); também é o índice da FK do estorno
  CONSTRAINT ux_pay_reverses       UNIQUE (reverses_payment_id)
);

-- Líquido/saldo da parcela e índice da FK composta da parcela
CREATE INDEX ix_pay_installment ON core.payments (clinic_id, installment_id);
-- Entradas do mês e fluxo de caixa (regime de caixa)
CREATE INDEX ix_pay_clinic_paid ON core.payments (clinic_id, paid_on, id);

-- ---------------------------------------------------------------------
-- Funções
-- ---------------------------------------------------------------------
CREATE FUNCTION core.guard_payment() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_patient     uuid;
  v_amount      numeric(12,2);
  v_status      varchar(10);
  v_anonymized  timestamptz;
  v_net         numeric;
  v_target_kind varchar(10);
  v_target_amt  numeric(12,2);
BEGIN
  -- A trava da parcela serializa os pagamentos dela e a leitura do saldo. Parcela invisível ou inexistente: RLS e FK respondem.
  SELECT i.patient_id, i.amount, i.status INTO v_patient, v_amount, v_status
    FROM core.installments i
   WHERE i.id = NEW.installment_id AND i.clinic_id = NEW.clinic_id
     FOR NO KEY UPDATE;
  IF NOT FOUND THEN
    RETURN NEW;
  END IF;

  -- FOR KEY SHARE: espera uma anonimização em curso e relê o paciente já anonimizado. Inativo: segue.
  SELECT p.anonymized_at INTO v_anonymized
    FROM core.patients p
   WHERE p.id = v_patient AND p.clinic_id = NEW.clinic_id
     FOR KEY SHARE;
  IF v_anonymized IS NOT NULL THEN
    RAISE EXCEPTION 'paciente anonimizado' USING ERRCODE = 'CR003';
  END IF;

  IF v_status = 'CANCELED' THEN
    RAISE EXCEPTION 'parcela cancelada nao recebe pagamento' USING ERRCODE = 'CR010';
  END IF;

  v_net := core.installment_net_paid(NEW.installment_id);   -- leitura nova depois da espera pela trava

  IF NEW.kind = 'PAYMENT' THEN
    IF v_net + NEW.amount > v_amount THEN
      RAISE EXCEPTION 'pagamento excede o saldo da parcela' USING ERRCODE = 'CR010';
    END IF;
  ELSE
    -- estorno: o alvo é um PAYMENT da mesma parcela e o valor é o mesmo (estorno parcial e estorno de estorno não existem).
    -- Alvo de outra parcela ou de outra clínica não aparece aqui: a FK composta responde 23503.
    SELECT p.kind, p.amount INTO v_target_kind, v_target_amt
      FROM core.payments p
     WHERE p.id = NEW.reverses_payment_id AND p.installment_id = NEW.installment_id AND p.clinic_id = NEW.clinic_id;
    IF FOUND AND (v_target_kind <> 'PAYMENT' OR v_target_amt <> NEW.amount) THEN
      RAISE EXCEPTION 'estorno exige o mesmo valor de um pagamento' USING ERRCODE = 'CR010';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

-- Mantém installments.status coerente com o líquido pago e incrementa version (uma entidade JPA velha falha por bloqueio otimista).
-- O UPDATE passa por guard_installment (consistente por construção) e pela auditoria da parcela.
CREATE FUNCTION core.sync_installment_status() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_net numeric := core.installment_net_paid(NEW.installment_id);
BEGIN
  UPDATE core.installments i
     SET status = CASE WHEN v_net >= i.amount THEN 'PAID' ELSE 'OPEN' END,
         version = i.version + 1
   WHERE i.id = NEW.installment_id AND i.clinic_id = NEW.clinic_id
     AND i.status IS DISTINCT FROM (CASE WHEN v_net >= i.amount THEN 'PAID' ELSE 'OPEN' END);
  RETURN NULL;
END;
$$;

-- O histórico do dinheiro só cresce: nenhum papel (nem o dono) altera, apaga ou esvazia (padrão do audit_log, V012)
CREATE FUNCTION core.payments_append_only() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog
AS $$
BEGIN
  RAISE EXCEPTION 'payments e somente de insercao' USING ERRCODE = '42501';
END;
$$;

REVOKE EXECUTE ON FUNCTION core.guard_payment()            FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.sync_installment_status()  FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.payments_append_only()     FROM PUBLIC;

CREATE TRIGGER trg_pay_guard
  BEFORE INSERT ON core.payments
  FOR EACH ROW EXECUTE FUNCTION core.guard_payment();

CREATE TRIGGER trg_pay_sync_status
  AFTER INSERT ON core.payments
  FOR EACH ROW EXECUTE FUNCTION core.sync_installment_status();

CREATE TRIGGER trg_pay_append_only
  BEFORE UPDATE OR DELETE ON core.payments
  FOR EACH ROW EXECUTE FUNCTION core.payments_append_only();

-- TRUNCATE não dispara trigger de linha: o de comando fecha essa porta
CREATE TRIGGER trg_pay_no_truncate
  BEFORE TRUNCATE ON core.payments
  FOR EACH STATEMENT EXECUTE FUNCTION core.payments_append_only();

ALTER TABLE core.payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.payments FORCE  ROW LEVEL SECURITY;

CREATE POLICY pay_sel ON core.payments
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY pay_ins ON core.payments
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND created_by = core.current_user_id()
              AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY pay_backup_sel ON core.payments
  FOR SELECT TO crm_backup
  USING (true);

-- Só SELECT e INSERT: sem política nem grant de UPDATE/DELETE/TRUNCATE
GRANT SELECT, INSERT ON core.payments TO crm_app;
GRANT SELECT         ON core.payments TO crm_backup;
