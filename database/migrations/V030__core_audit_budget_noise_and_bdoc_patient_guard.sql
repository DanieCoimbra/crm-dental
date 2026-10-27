-- =====================================================================
-- V030__core_audit_budget_noise_and_bdoc_patient_guard.sql | Fase 3 / auditoria de segurança e desempenho (2026-10-27)
-- Objetivo : (a) a edição de itens de um orçamento deixa de gerar uma linha de audit_log por item (o recálculo do total é um UPDATE
--            em budgets por linha de item, e cada UPDATE era auditado: substituir 50 itens gerava ~100 linhas). O UPDATE de budgets
--            só é auditado quando o conteúdo ou o status mudam (ou a version sobe); INSERT e DELETE continuam sempre auditados.
--            (b) core.budget_documents passa a recusar PDF de paciente anonimizado no próprio banco (CR003), como patients filhos
--            na V013, orçamentos na V024, parcelas na V026 e pagamentos na V027 (antes só o backend impedia, por lockWritable).
-- Executar : scripts/apply_migrations.sh, como crm_owner (membro de crm_definer, dono de core.write_audit()).
-- Reverter : database/rollback/U030__core_audit_budget_noise_and_bdoc_patient_guard.sql
-- Depende  : V012 (write_audit), V013 (forbid_child_of_anonymized), V024 (budgets, refresh_budget_total), V025, V028 (trg_budgets_audit).
-- Erros    : 28000 gravação sem app.user_id (inclui agora a edição de item, que não gera mais auditoria própria); CR003 PDF de
--            paciente anonimizado.
-- Observações:
--   * O WHEN do UPDATE é avaliado DEPOIS dos triggers BEFORE (guard_budget_update recalcula total_amount): o valor visto é o final.
--     Mudança só em total_amount/updated_at (recálculo por item) não audita. Mudança de discount_amount, appointment_id, valid_until,
--     notes, status ou version audita. A edição de itens pelo backend sobe a version (PESSIMISTIC_FORCE_INCREMENT), então gera 1 linha.
--   * A auditoria deixa de ser "toda gravação gera uma linha" para os itens de orçamento (budget_items nunca foi auditada): para não
--     abrir uma gravação anônima, refresh_budget_total passa a exigir app.user_id (28000), o mesmo erro que o write_audit dava na cascata.
--   * CREATE TRIGGER exige EXECUTE do criador em core.write_audit() (dono crm_definer, revogado de PUBLIC): concessão temporária feita
--     COMO crm_definer e fechada ao fim, na mesma transação (padrão da V020/V028).
--   * forbid_child_of_anonymized() é reaproveitada: lê NEW.patient_id/NEW.clinic_id e trava a linha do paciente com FOR KEY SHARE
--     (espera uma anonimização em curso e relê o paciente já anonimizado). Dispara depois de trg_bdoc_before_insert (ordem alfabética).
-- =====================================================================

-- (a) o recálculo do total passa a exigir a sessão com usuário (substitui o efeito colateral da auditoria em cascata)
CREATE OR REPLACE FUNCTION core.refresh_budget_total() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_budget uuid;
  v_clinic uuid;
BEGIN
  IF NULLIF(pg_catalog.current_setting('app.user_id', true), '') IS NULL THEN
    RAISE EXCEPTION 'edicao de item exige app.user_id' USING ERRCODE = '28000';
  END IF;
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

-- concessão temporária (como dono da função)
SET LOCAL ROLE crm_definer;
GRANT EXECUTE ON FUNCTION core.write_audit() TO crm_owner;
RESET ROLE;

DROP TRIGGER trg_budgets_audit ON core.budgets;

CREATE TRIGGER trg_budgets_audit_insdel
  AFTER INSERT OR DELETE ON core.budgets
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();

CREATE TRIGGER trg_budgets_audit_upd
  AFTER UPDATE ON core.budgets
  FOR EACH ROW
  WHEN (OLD.status          IS DISTINCT FROM NEW.status
     OR OLD.version         IS DISTINCT FROM NEW.version
     OR OLD.discount_amount IS DISTINCT FROM NEW.discount_amount
     OR OLD.appointment_id  IS DISTINCT FROM NEW.appointment_id
     OR OLD.valid_until     IS DISTINCT FROM NEW.valid_until
     OR OLD.notes           IS DISTINCT FROM NEW.notes)
  EXECUTE FUNCTION core.write_audit();

-- fechamento da concessão temporária
SET LOCAL ROLE crm_definer;
REVOKE EXECUTE ON FUNCTION core.write_audit() FROM crm_owner;
RESET ROLE;

-- (b) PDF de paciente anonimizado: recusado pelo banco
CREATE TRIGGER trg_bdoc_no_anonymized_parent
  BEFORE INSERT ON core.budget_documents
  FOR EACH ROW EXECUTE FUNCTION core.forbid_child_of_anonymized();
