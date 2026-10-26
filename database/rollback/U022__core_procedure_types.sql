-- =====================================================================
-- U022__core_procedure_types.sql | reversão MANUAL de V022 (nunca automática; APAGA O CATÁLOGO DE PROCEDIMENTOS)
-- Executar : como crm_owner, por decisão humana, DEPOIS de reverter U023 e U024 (a FK de appointments e a FK dos itens dependem
--            da tabela). Faça backup antes; depois de haver dados, restaure do pg_dump em vez de reverter.
-- =====================================================================
BEGIN;
DROP TABLE IF EXISTS core.procedure_types;   -- leva os triggers, índices e políticas junto
COMMIT;
