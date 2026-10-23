-- =====================================================================
-- V019__core_notes_appointment_link.sql | Fase 2 / F05-note-appointment-link
-- Objetivo : vínculo OPCIONAL da nota clínica com o atendimento em que ela ocorreu (clinical_notes.appointment_id),
--            garantindo por FK composta que o agendamento é da mesma clínica E do mesmo paciente da nota.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U019__core_notes_appointment_link.sql
-- Depende  : V010 (clinical_notes), V015 (appointments e ux_appt_id_patient_clinic).
-- Erros    : 23503 agendamento inexistente, de outra clínica ou de outro paciente.
-- Observações:
--   * Aditiva: coluna NULA, sem default; nenhuma linha existente é reescrita nem validada contra a FK (MATCH SIMPLE:
--     com appointment_id nulo a FK não é verificada).
--   * "Uma nota por atendimento" é regra do backend: sem UNIQUE, várias notas no mesmo agendamento são permitidas.
--   * A coluna é MUTÁVEL (o dentista pode vincular a nota depois) e NÃO é cifrada (é um uuid, sem dado clínico):
--     o trigger de colunas imutáveis de clinical_notes e as políticas/grants da Fase 1 não mudam.
--   * ON DELETE RESTRICT por garantia: crm_app não apaga agendamento.
-- =====================================================================

ALTER TABLE core.clinical_notes ADD COLUMN appointment_id uuid;

ALTER TABLE core.clinical_notes
  ADD CONSTRAINT fk_notes_appointment
  FOREIGN KEY (appointment_id, patient_id, clinic_id)
  REFERENCES core.appointments (id, patient_id, clinic_id) ON DELETE RESTRICT;

-- Notas de um atendimento e índice da FK; parcial: não pesa nas notas sem vínculo
CREATE INDEX ix_notes_appointment ON core.clinical_notes (clinic_id, appointment_id)
  WHERE appointment_id IS NOT NULL;
