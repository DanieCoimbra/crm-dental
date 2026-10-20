-- =====================================================================
-- U001__core_privileges.sql | reversão MANUAL de V001 (nunca automática)
-- Executar : como crm_owner, por decisão humana.
-- Efeito   : remove o USAGE dos papéis do CRM e restaura o EXECUTE padrão para PUBLIC
--            em funções futuras. Não apaga o schema nem os papéis.
-- =====================================================================
REVOKE USAGE ON SCHEMA core FROM crm_app, crm_backup, crm_definer;
ALTER DEFAULT PRIVILEGES FOR ROLE crm_owner GRANT EXECUTE ON FUNCTIONS TO PUBLIC;
