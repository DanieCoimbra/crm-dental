-- =====================================================================
-- U031__core_users_cro.sql | reversão MANUAL de V031 (nunca automática; APAGA O CRO JÁ INFORMADO)
-- Executar : como crm_owner, por decisão humana, DEPOIS de U032 (clinical_documents usa o CRO do emissor).
--            Com CRO preenchido, restaure do pg_dump em vez de reverter (o dono não enxerga linhas de users sob FORCE RLS, então a
--            reversão não consegue conferir se há CRO gravado).
-- =====================================================================
BEGIN;
DROP TRIGGER IF EXISTS trg_users_guard_cro ON core.users;
DROP FUNCTION IF EXISTS core.guard_user_cro();
ALTER TABLE core.users DROP CONSTRAINT IF EXISTS ck_users_cro_state;
ALTER TABLE core.users DROP CONSTRAINT IF EXISTS ck_users_cro_fmt;
ALTER TABLE core.users DROP CONSTRAINT IF EXISTS ck_users_cro_role;
ALTER TABLE core.users DROP CONSTRAINT IF EXISTS ck_users_cro_pair;
ALTER TABLE core.users DROP COLUMN IF EXISTS cro_state;
ALTER TABLE core.users DROP COLUMN IF EXISTS cro_number;
COMMIT;
