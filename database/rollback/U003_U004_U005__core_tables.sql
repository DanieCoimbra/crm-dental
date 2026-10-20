-- =====================================================================
-- Reversão MANUAL de V003, V004 e V005 (nunca automática; APAGA OS DADOS das tabelas).
-- Executar : como crm_owner, por decisão humana, em ordem inversa de dependência.
-- Antes    : faça backup (workflow db-backup.yml). Em produção, prefira migrações aditivas
--            a reverter.
-- =====================================================================
DROP TABLE IF EXISTS core.refresh_tokens;   -- U005
DROP TABLE IF EXISTS core.users;            -- U004
DROP TABLE IF EXISTS core.clinics;          -- U003
