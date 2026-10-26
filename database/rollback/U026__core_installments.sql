-- =====================================================================
-- U026__core_installments.sql | reversão MANUAL de V026 (nunca automática; APAGA TODAS AS PARCELAS)
-- Executar : como crm_owner, por decisão humana, DEPOIS de reverter U027 (payments), U028 e U029 e ANTES de U024. Faça backup
--            antes; depois de haver dados, restaure do pg_dump em vez de reverter.
-- =====================================================================
BEGIN;
DROP TABLE IF EXISTS core.installments;      -- leva os triggers (inclusive o adiável), índices e políticas junto
DROP FUNCTION IF EXISTS core.check_installments_sum();
DROP FUNCTION IF EXISTS core.guard_installment();
DROP FUNCTION IF EXISTS core.installment_net_paid(uuid);
COMMIT;
