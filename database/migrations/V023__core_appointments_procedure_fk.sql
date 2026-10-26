-- =====================================================================
-- V023__core_appointments_procedure_fk.sql | Fase 3 / F01-procedure-types
-- Objetivo : o agendamento passa a apontar para um procedimento DA MESMA CLÍNICA (FK composta). A coluna
--            appointments.procedure_type_id já existia (V015), nula e sem FK; nenhuma linha é reescrita.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U023__core_appointments_procedure_fk.sql
-- Depende  : V015 (appointments), V022 (procedure_types).
-- Erros    : 23503 procedimento inexistente ou de outra clínica.
-- Observações:
--   * Com MATCH SIMPLE a FK não é verificada quando procedure_type_id é nulo (todas as linhas atuais): a validação é imediata,
--     sem NOT VALID. O RI check não passa pela RLS; o isolamento vem do clinic_id na chave composta.
--   * Impedir agendamento de procedimento INATIVO é regra do backend.
-- =====================================================================

ALTER TABLE core.appointments ADD CONSTRAINT fk_appt_procedure
  FOREIGN KEY (procedure_type_id, clinic_id) REFERENCES core.procedure_types (id, clinic_id) ON DELETE RESTRICT;

-- Índice da FK (e "agendamentos do procedimento"); parcial: quase todas as linhas têm o campo nulo
CREATE INDEX ix_appt_procedure ON core.appointments (clinic_id, procedure_type_id)
  WHERE procedure_type_id IS NOT NULL;
