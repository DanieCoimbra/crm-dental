-- =====================================================================
-- U027__core_payments.sql | reversão MANUAL de V027 (nunca automática; APAGA TODOS OS PAGAMENTOS)
-- Executar : como crm_owner, por decisão humana, DEPOIS de reverter U028 (trigger de auditoria) e ANTES de U026. A tabela é
--            append-only para todos os papéis, mas o DROP TABLE é DDL do dono e não passa pelos triggers. Faça backup antes;
--            depois de haver dados, restaure do pg_dump em vez de reverter.
-- =====================================================================
BEGIN;
DROP TABLE IF EXISTS core.payments;          -- leva os triggers, índices e políticas junto
DROP FUNCTION IF EXISTS core.payments_append_only();
DROP FUNCTION IF EXISTS core.sync_installment_status();
DROP FUNCTION IF EXISTS core.guard_payment();
COMMIT;
