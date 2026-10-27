-- =====================================================================
-- U030__core_audit_budget_noise_and_bdoc_patient_guard.sql | reversão MANUAL de V030 (nunca automática)
-- Executar : como crm_owner, por decisão humana, ANTES de U029. Restaura o trigger único de auditoria de budgets (todo UPDATE gera
--            linha, como na V028), o corpo original de refresh_budget_total (V024) e remove a guarda de paciente anonimizado em
--            budget_documents. Não apaga nem altera linhas de audit_log (o log é só de inserção).
-- =====================================================================
BEGIN;

DROP TRIGGER IF EXISTS trg_bdoc_no_anonymized_parent ON core.budget_documents;

CREATE OR REPLACE FUNCTION core.refresh_budget_total() RETURNS trigger
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

SET LOCAL ROLE crm_definer;
GRANT EXECUTE ON FUNCTION core.write_audit() TO crm_owner;
RESET ROLE;

DROP TRIGGER IF EXISTS trg_budgets_audit_upd    ON core.budgets;
DROP TRIGGER IF EXISTS trg_budgets_audit_insdel ON core.budgets;
CREATE TRIGGER trg_budgets_audit
  AFTER INSERT OR UPDATE OR DELETE ON core.budgets
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();

SET LOCAL ROLE crm_definer;
REVOKE EXECUTE ON FUNCTION core.write_audit() FROM crm_owner;
RESET ROLE;

COMMIT;
