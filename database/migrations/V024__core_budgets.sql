-- =====================================================================
-- V024__core_budgets.sql | Fase 3 / F02-budgets
-- Objetivo : orçamento (core.budgets) e itens (core.budget_items) com a integridade do dinheiro NO BANCO (decisão C1):
--            número sequencial por clínica, total calculado pelo banco (Σ line_total − desconto), snapshot de descrição e preço
--            copiados do catálogo, itens só editáveis em DRAFT, estados terminais, carimbos sent_at/decided_at do banco e guarda do
--            paciente (CR003/CR005). Recepção lê e não grava.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U024__core_budgets.sql
-- Depende  : V022 (procedure_types), V015 (appointments e seu UNIQUE (id, patient_id, clinic_id)), V008 (patients), V004 (users),
--            V002 (triggers genéricos), V007 (funções de sessão).
-- Erros    : CR007 orçamento congelado (item ou conteúdo alterado fora de DRAFT); CR008 status inválido (nasce fora de DRAFT, volta
--            a DRAFT, sai de estado terminal, enviar/aprovar sem itens ou com total 0); CR003 paciente anonimizado; CR005 paciente
--            inativo; 23514 CHECK, teto ou coluna imutável; 23503 FK; 23505 posição repetida; 42501 papel ou autoria.
-- Observações:
--   * Os guards devolvem NEW quando a linha-pai não é visível (S16): o WITH CHECK do INSERT roda DEPOIS dos triggers BEFORE, e assim
--     a recepção recebe 42501 (RLS) e não um erro de FK.
--   * O orçamento é o lock que serializa a edição de itens e a mudança de status (SELECT ... FOR NO KEY UPDATE em guard_budget_item).
--   * O desconto do orçamento só entra por UPDATE, depois dos itens: no INSERT o total nasce 0 − desconto e viola ck_budgets_total (S7).
--   * Número: lock consultivo da clínica + max(number) + 1, sem tabela de contador; exige READ COMMITTED (S2).
--   * UNIQUE (id, clinic_id) em budgets: alvo da FK de budget_items (exceção ao S1 da Fase 2: volume de dezenas de linhas por mês).
--   * created_by (FK composta) não tem índice de propósito: users nunca é apagado.
--   * Anonimização (V029): sob a flag core.anonymizing = 'on' E current_user = 'crm_definer' (a flag sozinha é forjável por qualquer
--     sessão), só notes pode virar NULL, em qualquer estado.
--   * crm_app consegue tomar o lock consultivo da numeração de qualquer clínica (pg_advisory_xact_lock é público): negação de serviço só
--     por SQL arbitrário; risco aceito e documentado na spec (seção 11.1).
-- =====================================================================

CREATE TABLE core.budgets (
  id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id       uuid          NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  number          integer       NOT NULL,                      -- preenchido pelo trigger
  patient_id      uuid          NOT NULL,
  appointment_id  uuid,
  status          varchar(10)   NOT NULL DEFAULT 'DRAFT',
  discount_amount numeric(12,2) NOT NULL DEFAULT 0,
  total_amount    numeric(12,2) NOT NULL DEFAULT 0,            -- calculado pelo banco
  valid_until     date,
  notes           varchar(500),
  sent_at         timestamptz,
  decided_at      timestamptz,
  created_by      uuid          NOT NULL,
  created_at      timestamptz   NOT NULL DEFAULT now(),
  updated_at      timestamptz   NOT NULL DEFAULT now(),
  version         bigint        NOT NULL DEFAULT 0,
  CONSTRAINT ck_budgets_number   CHECK (number >= 1),
  CONSTRAINT ck_budgets_status   CHECK (status IN ('DRAFT', 'SENT', 'APPROVED', 'REJECTED')),
  CONSTRAINT ck_budgets_discount CHECK (discount_amount BETWEEN 0 AND 999999.99),
  CONSTRAINT ck_budgets_total    CHECK (total_amount BETWEEN 0 AND 999999.99),
  CONSTRAINT ck_budgets_valid    CHECK (valid_until IS NULL OR valid_until >= DATE '2000-01-01'),
  CONSTRAINT ck_budgets_notes    CHECK (notes IS NULL OR char_length(btrim(notes)) >= 1),
  CONSTRAINT ck_budgets_stamps   CHECK (
       (status = 'DRAFT' AND sent_at IS NULL AND decided_at IS NULL)
    OR (status = 'SENT'  AND sent_at IS NOT NULL AND decided_at IS NULL)
    OR ((status = 'APPROVED' OR status = 'REJECTED') AND decided_at IS NOT NULL AND (sent_at IS NULL OR sent_at <= decided_at))),
  CONSTRAINT fk_budgets_patient     FOREIGN KEY (patient_id, clinic_id)
    REFERENCES core.patients (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_budgets_appointment FOREIGN KEY (appointment_id, patient_id, clinic_id)
    REFERENCES core.appointments (id, patient_id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_budgets_created_by  FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_budgets_clinic_number     UNIQUE (clinic_id, number),
  CONSTRAINT ux_budgets_id_clinic         UNIQUE (id, clinic_id),
  CONSTRAINT ux_budgets_id_patient_clinic UNIQUE (id, patient_id, clinic_id)
);

-- Orçamentos do paciente (keyset) e índice da FK composta do paciente
CREATE INDEX ix_budgets_patient     ON core.budgets (clinic_id, patient_id, created_at DESC, id DESC);
-- Lista por status
CREATE INDEX ix_budgets_status      ON core.budgets (clinic_id, status, created_at DESC, id DESC);
-- Faturamento por período (competência). Predicado com = (e não IN): ver a nota da V015.
CREATE INDEX ix_budgets_decided     ON core.budgets (clinic_id, decided_at, id) WHERE status = 'APPROVED';
-- Índice da FK do atendimento de origem
CREATE INDEX ix_budgets_appointment ON core.budgets (clinic_id, appointment_id) WHERE appointment_id IS NOT NULL;

CREATE TABLE core.budget_items (
  id                uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id         uuid          NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  budget_id         uuid          NOT NULL,
  procedure_type_id uuid          NOT NULL,
  description       varchar(120)  NOT NULL,                    -- copiada de procedure_types.name pelo trigger
  quantity          smallint      NOT NULL DEFAULT 1,
  unit_price        numeric(12,2) NOT NULL,                    -- copiado de procedure_types.base_price pelo trigger
  discount_amount   numeric(12,2) NOT NULL DEFAULT 0,
  line_total        numeric(12,2) GENERATED ALWAYS AS (quantity * unit_price - discount_amount) STORED,
  position          smallint      NOT NULL,
  created_at        timestamptz   NOT NULL DEFAULT now(),
  updated_at        timestamptz   NOT NULL DEFAULT now(),
  version           bigint        NOT NULL DEFAULT 0,
  CONSTRAINT ck_items_qty      CHECK (quantity BETWEEN 1 AND 999),
  CONSTRAINT ck_items_price    CHECK (unit_price BETWEEN 0 AND 999999.99),
  CONSTRAINT ck_items_discount CHECK (discount_amount BETWEEN 0 AND 999999.99),
  CONSTRAINT ck_items_line     CHECK (line_total BETWEEN 0 AND 999999.99),
  CONSTRAINT ck_items_position CHECK (position BETWEEN 1 AND 50),
  CONSTRAINT fk_items_budget    FOREIGN KEY (budget_id, clinic_id)
    REFERENCES core.budgets (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_items_procedure FOREIGN KEY (procedure_type_id, clinic_id)
    REFERENCES core.procedure_types (id, clinic_id) ON DELETE RESTRICT,
  -- No máximo 50 itens por orçamento (posição 1..50 + unicidade). Adiável: reordenar troca posições numa transação.
  CONSTRAINT ux_items_budget_position UNIQUE (budget_id, position) DEFERRABLE INITIALLY DEFERRED
);

-- Índice da FK do procedimento (clinic_id à frente); o da FK do orçamento é ux_items_budget_position (budget_id à frente)
CREATE INDEX ix_items_procedure ON core.budget_items (clinic_id, procedure_type_id);

-- ---------------------------------------------------------------------
-- Funções
-- ---------------------------------------------------------------------
-- Σ line_total dos itens do orçamento. SECURITY INVOKER: lê sob a RLS do chamador, então só devolve o que ele enxerga.
CREATE FUNCTION core.budget_items_total(p_budget uuid) RETURNS numeric
  LANGUAGE sql STABLE
  SET search_path = pg_catalog, pg_temp
AS $$
  SELECT COALESCE(sum(i.line_total), 0) FROM core.budget_items i WHERE i.budget_id = p_budget
$$;

-- INSERT do orçamento: nasce DRAFT, paciente ativo e não anonimizado, número sequencial por clínica, total inicial.
CREATE FUNCTION core.budgets_before_insert() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_active     boolean;
  v_anonymized timestamptz;
BEGIN
  IF NEW.status <> 'DRAFT' THEN
    RAISE EXCEPTION 'orcamento nasce em rascunho' USING ERRCODE = 'CR008';
  END IF;

  -- FOR KEY SHARE: se uma anonimização está em curso (FOR UPDATE), este INSERT espera o commit e relê o paciente já anonimizado
  -- (mesmo padrão da V013/V015). Sem linha: segue; a FK composta responde 23503.
  SELECT p.active, p.anonymized_at INTO v_active, v_anonymized
    FROM core.patients p
   WHERE p.id = NEW.patient_id AND p.clinic_id = NEW.clinic_id
     FOR KEY SHARE;
  IF v_anonymized IS NOT NULL THEN
    RAISE EXCEPTION 'paciente anonimizado' USING ERRCODE = 'CR003';
  END IF;
  IF v_active IS FALSE THEN
    RAISE EXCEPTION 'paciente inativo' USING ERRCODE = 'CR005';
  END IF;

  -- Número: serializa a criação por clínica até o COMMIT; o max enxerga o orçamento confirmado pela transação anterior
  -- (READ COMMITTED: novo snapshot a cada comando). O valor enviado pelo cliente é sempre sobrescrito.
  PERFORM pg_advisory_xact_lock(hashtextextended('budgets:' || NEW.clinic_id::text, 0));
  NEW.number := COALESCE((SELECT max(b.number) FROM core.budgets b WHERE b.clinic_id = NEW.clinic_id), 0) + 1;

  -- Sem itens ainda: com desconto > 0 o total fica negativo e ck_budgets_total reprova (23514)
  NEW.total_amount := 0 - NEW.discount_amount;
  NEW.sent_at := NULL;
  NEW.decided_at := NULL;
  RETURN NEW;
END;
$$;

-- UPDATE do orçamento: total, carimbos, congelamento e estados terminais (ver a tabela de decisão em docs/.../F02-budgets/spec.md)
CREATE FUNCTION core.guard_budget_update() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_count integer;
  v_sum   numeric;
BEGIN
  -- Anonimização (flag local da função core.anonymize_patient): só notes pode virar NULL, em qualquer estado.
  -- A flag é um GUC que qualquer sessão consegue forjar; por isso a exceção exige TAMBÉM current_user = 'crm_definer', que só vale dentro
  -- da função SECURITY DEFINER (o trigger roda com o papel de quem executa a anonimização). Sem isso o dentista congelaria e descongelaria
  -- as notes do orçamento enviado com um set_config.
  IF COALESCE(current_setting('core.anonymizing', true), '') = 'on' AND current_user = 'crm_definer' THEN
    IF (NEW.status, NEW.discount_amount, NEW.total_amount, NEW.appointment_id, NEW.valid_until, NEW.sent_at, NEW.decided_at)
         IS DISTINCT FROM
       (OLD.status, OLD.discount_amount, OLD.total_amount, OLD.appointment_id, OLD.valid_until, OLD.sent_at, OLD.decided_at)
       OR (NEW.notes IS NOT NULL AND NEW.notes IS DISTINCT FROM OLD.notes) THEN
      RAISE EXCEPTION 'anonimizacao so limpa as notas do orcamento' USING ERRCODE = 'CR007';
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.status = 'DRAFT' THEN
    -- total e carimbos são do banco: o valor enviado pelo cliente é ignorado
    SELECT count(*), COALESCE(sum(i.line_total), 0) INTO v_count, v_sum
      FROM core.budget_items i WHERE i.budget_id = OLD.id;
    NEW.total_amount := v_sum - NEW.discount_amount;
    IF NEW.status = 'DRAFT' THEN
      NEW.sent_at := NULL;
      NEW.decided_at := NULL;
    ELSE
      -- enviar ou aprovar exige itens e total > 0; recusar (descartar o rascunho) não exige nada
      IF (NEW.status = 'SENT' OR NEW.status = 'APPROVED') AND (v_count = 0 OR NEW.total_amount <= 0) THEN
        RAISE EXCEPTION 'orcamento sem itens ou com total zero' USING ERRCODE = 'CR008';
      END IF;
      IF NEW.status = 'SENT' THEN
        NEW.sent_at := now();
        NEW.decided_at := NULL;
      ELSE                                  -- APPROVED ou REJECTED direto (decisão S6)
        NEW.sent_at := NULL;
        NEW.decided_at := now();
      END IF;
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.status = 'SENT' THEN
    IF NOT (NEW.status = 'SENT' OR NEW.status = 'APPROVED' OR NEW.status = 'REJECTED') THEN
      RAISE EXCEPTION 'orcamento enviado nao volta a rascunho' USING ERRCODE = 'CR008';
    END IF;
  ELSIF NEW.status <> OLD.status THEN        -- APPROVED ou REJECTED: terminais
    RAISE EXCEPTION 'orcamento decidido nao muda de status' USING ERRCODE = 'CR008';
  END IF;

  -- Fora de DRAFT o conteúdo é congelado. total e carimbos são do banco: o valor do cliente é ignorado.
  IF (NEW.discount_amount, NEW.appointment_id, NEW.valid_until, NEW.notes)
       IS DISTINCT FROM (OLD.discount_amount, OLD.appointment_id, OLD.valid_until, OLD.notes) THEN
    RAISE EXCEPTION 'orcamento congelado' USING ERRCODE = 'CR007';
  END IF;
  NEW.total_amount := OLD.total_amount;
  NEW.sent_at := OLD.sent_at;
  IF OLD.status = 'SENT' AND NEW.status <> 'SENT' THEN
    NEW.decided_at := now();
  ELSE
    NEW.decided_at := OLD.decided_at;
  END IF;
  RETURN NEW;
END;
$$;

-- Itens: só mudam com o orçamento em DRAFT. O lock do orçamento serializa a edição de itens com o envio e entre itens.
CREATE FUNCTION core.guard_budget_item() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_budget uuid;
  v_clinic uuid;
  v_status varchar(10);
  v_name   varchar(80);
  v_price  numeric(12,2);
BEGIN
  IF TG_OP = 'DELETE' THEN
    v_budget := OLD.budget_id; v_clinic := OLD.clinic_id;
  ELSE
    v_budget := NEW.budget_id; v_clinic := NEW.clinic_id;
  END IF;

  SELECT b.status INTO v_status
    FROM core.budgets b
   WHERE b.id = v_budget AND b.clinic_id = v_clinic
     FOR NO KEY UPDATE;
  IF NOT FOUND THEN                          -- invisível (outra clínica, papel sem acesso) ou inexistente: RLS/FK respondem
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF TG_OP = 'INSERT' THEN
      -- preenche o snapshot sem erro (se o procedimento existir) para o NOT NULL não responder antes da RLS e da FK
      SELECT pt.name, pt.base_price INTO v_name, v_price
        FROM core.procedure_types pt
       WHERE pt.id = NEW.procedure_type_id AND pt.clinic_id = NEW.clinic_id;
      IF FOUND THEN
        NEW.description := v_name;
        NEW.unit_price := v_price;
      END IF;
    END IF;
    RETURN NEW;
  END IF;
  IF v_status <> 'DRAFT' THEN
    RAISE EXCEPTION 'orcamento congelado' USING ERRCODE = 'CR007';
  END IF;

  IF TG_OP = 'INSERT' THEN
    -- snapshot do catálogo: o valor enviado pelo cliente é sobrescrito (decisão S4)
    SELECT pt.name, pt.base_price INTO v_name, v_price
      FROM core.procedure_types pt
     WHERE pt.id = NEW.procedure_type_id AND pt.clinic_id = NEW.clinic_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'procedimento inexistente na clinica' USING ERRCODE = '23503';
    END IF;
    NEW.description := v_name;
    NEW.unit_price := v_price;
  END IF;

  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

-- Recalcula o total do orçamento depois de cada mudança de item. O UPDATE passa por guard_budget_update, que recalcula o mesmo valor;
-- não altera version. Se o total ficar negativo ou acima do teto, ck_budgets_total reprova (23514) e a operação do item reverte.
CREATE FUNCTION core.refresh_budget_total() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_budget uuid;
  v_clinic uuid;
BEGIN
  IF TG_OP = 'DELETE' THEN
    v_budget := OLD.budget_id; v_clinic := OLD.clinic_id;
  ELSE
    v_budget := NEW.budget_id; v_clinic := NEW.clinic_id;
  END IF;
  UPDATE core.budgets b
     SET total_amount = core.budget_items_total(b.id) - b.discount_amount
   WHERE b.id = v_budget AND b.clinic_id = v_clinic;
  RETURN NULL;
END;
$$;

REVOKE EXECUTE ON FUNCTION core.budget_items_total(uuid)  FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.budgets_before_insert()   FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.guard_budget_update()     FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.guard_budget_item()       FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.refresh_budget_total()    FROM PUBLIC;
-- Única função com EXECUTE ao crm_app: é chamada de dentro dos triggers, que rodam com o papel do chamador (invoker)
GRANT  EXECUTE ON FUNCTION core.budget_items_total(uuid)  TO crm_app;

-- ---------------------------------------------------------------------
-- Triggers (do mesmo evento, disparam em ordem alfabética do nome)
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_budgets_before_insert
  BEFORE INSERT ON core.budgets
  FOR EACH ROW EXECUTE FUNCTION core.budgets_before_insert();

CREATE TRIGGER trg_budgets_forbid_immutable
  BEFORE UPDATE ON core.budgets
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'clinic_id', 'number', 'patient_id', 'created_by', 'created_at');

CREATE TRIGGER trg_budgets_guard_update
  BEFORE UPDATE ON core.budgets
  FOR EACH ROW EXECUTE FUNCTION core.guard_budget_update();

CREATE TRIGGER trg_budgets_set_updated_at
  BEFORE UPDATE ON core.budgets
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_items_forbid_immutable
  BEFORE UPDATE ON core.budget_items
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns(
    'id', 'clinic_id', 'budget_id', 'procedure_type_id', 'description', 'unit_price', 'created_at');

CREATE TRIGGER trg_items_guard
  BEFORE INSERT OR UPDATE OR DELETE ON core.budget_items
  FOR EACH ROW EXECUTE FUNCTION core.guard_budget_item();

CREATE TRIGGER trg_items_set_updated_at
  BEFORE UPDATE ON core.budget_items
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_items_refresh_total
  AFTER INSERT OR UPDATE OR DELETE ON core.budget_items
  FOR EACH ROW EXECUTE FUNCTION core.refresh_budget_total();

-- ---------------------------------------------------------------------
-- RLS
-- ---------------------------------------------------------------------
ALTER TABLE core.budgets      ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.budgets      FORCE  ROW LEVEL SECURITY;
ALTER TABLE core.budget_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.budget_items FORCE  ROW LEVEL SECURITY;

CREATE POLICY budgets_sel ON core.budgets
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));

CREATE POLICY budgets_ins ON core.budgets
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND created_by = core.current_user_id()
              AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY budgets_upd ON core.budgets
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY budgets_backup_sel ON core.budgets
  FOR SELECT TO crm_backup
  USING (true);

CREATE POLICY items_sel ON core.budget_items
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));

CREATE POLICY items_ins ON core.budget_items
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY items_upd ON core.budget_items
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

-- A exigência de orçamento em DRAFT é do trigger trg_items_guard (CR007), não da política
CREATE POLICY items_del ON core.budget_items
  FOR DELETE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY items_backup_sel ON core.budget_items
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE em budgets (descartar rascunho é DRAFT -> REJECTED). budget_items é a ÚNICA tabela da fase com DELETE para crm_app.
GRANT SELECT, INSERT, UPDATE         ON core.budgets      TO crm_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON core.budget_items TO crm_app;
GRANT SELECT ON core.budgets      TO crm_backup;
GRANT SELECT ON core.budget_items TO crm_backup;
