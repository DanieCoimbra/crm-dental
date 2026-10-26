-- =====================================================================
-- U024__core_budgets.sql | reversão MANUAL de V024 (nunca automática; APAGA TODOS OS ORÇAMENTOS E ITENS)
-- Executar : como crm_owner, por decisão humana, DEPOIS de reverter U025, U026 e U029 (budget_documents, installments e as
--            políticas do definer dependem da tabela) e U028 (trigger de auditoria). Faça backup antes; depois de haver
--            dados, restaure do pg_dump em vez de reverter.
-- =====================================================================
BEGIN;
DROP TABLE IF EXISTS core.budget_items;      -- leva os triggers, índices e políticas junto
DROP TABLE IF EXISTS core.budgets;
DROP FUNCTION IF EXISTS core.refresh_budget_total();
DROP FUNCTION IF EXISTS core.guard_budget_item();
DROP FUNCTION IF EXISTS core.guard_budget_update();
DROP FUNCTION IF EXISTS core.budgets_before_insert();
DROP FUNCTION IF EXISTS core.budget_items_total(uuid);
COMMIT;
