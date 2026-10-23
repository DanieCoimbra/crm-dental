-- =====================================================================
-- U015__core_appointments.sql | reversão MANUAL de V015 (nunca automática; APAGA TODOS OS AGENDAMENTOS)
-- Executar : como crm_owner, por decisão humana, DEPOIS de reverter V019, V020 e V021 (a FK de clinical_notes, o trigger de
--            auditoria e as políticas do definer dependem da tabela). Faça backup antes; depois de haver dados, restaure
--            do pg_dump em vez de reverter.
-- =====================================================================
BEGIN;
DROP TABLE IF EXISTS core.appointments;      -- leva os triggers, índices, constraint de exclusão e políticas junto
DROP FUNCTION IF EXISTS core.guard_appointment_patient();
COMMIT;
