-- =====================================================================
-- V015__core_appointments.sql | Fase 2 / F02-appointments
-- Objetivo : agenda da clínica (core.appointments). Dois atendimentos nunca ocupam o mesmo horário na mesma clínica,
--            nem sob concorrência (EXCLUDE USING gist + btree_gist); a recepção só grava o que lhe cabe (status e
--            linhas), por RLS; paciente inativo ou anonimizado não recebe agendamento novo.
-- Executar : scripts/apply_migrations.sh, como crm_owner. Exige bootstrap/20_extensions.sql (btree_gist) já executado.
-- Reverter : database/rollback/U015__core_appointments.sql
-- Depende  : V008 (patients), V004 (users), V007 (funções de sessão), V002 (triggers genéricos), V014 (fuso).
-- Erros    : 23P01 conflito de horário (ex_appt_no_overlap); 23514 CHECK ou coluna imutável; 23503 FK (paciente de outra
--            clínica ou inexistente); CR003 paciente anonimizado; CR005 paciente inativo; 42501 papel ou autoria.
-- Observações:
--   * A ORDEM das transições de status é do backend (decisão A4); o banco só valida a lista e o que cada papel grava.
--   * procedure_type_id não tem FK nem índice: a tabela de procedimentos é da Fase 3.
--   * created_by (FK composta) não tem índice de propósito: users nunca é apagado.
--   * UNIQUE (id, patient_id, clinic_id) é o alvo da FK composta de clinical_notes (V019); não há UNIQUE (id, clinic_id)
--     (índice redundante em tabela de escrita frequente).
--   * notes é texto CLARO e curto: não é lugar de dado clínico (esse vai no prontuário cifrado).
-- =====================================================================

-- Guarda: sem btree_gist o EXCLUDE não existe e a Fase 2 perde a garantia de conflito (sem plano B)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist') THEN
    RAISE EXCEPTION 'btree_gist ausente: execute bootstrap/20_extensions.sql como postgres e reaplique a V015';
  END IF;
END $$;

CREATE TABLE core.appointments (
  id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id         uuid        NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  patient_id        uuid        NOT NULL,
  starts_at         timestamptz NOT NULL,
  ends_at           timestamptz NOT NULL,
  status            varchar(15) NOT NULL DEFAULT 'SCHEDULED',
  notes             varchar(500),
  procedure_type_id uuid,
  created_by        uuid        NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  version           bigint      NOT NULL DEFAULT 0,
  CONSTRAINT ck_appt_status       CHECK (status IN ('SCHEDULED', 'CONFIRMED', 'IN_PROGRESS', 'COMPLETED', 'NO_SHOW', 'CANCELED')),
  CONSTRAINT ck_appt_range        CHECK (ends_at > starts_at),
  CONSTRAINT ck_appt_start_minute CHECK (mod(extract(epoch FROM starts_at), 60) = 0),
  CONSTRAINT ck_appt_duration     CHECK ((ends_at - starts_at) BETWEEN interval '5 minutes' AND interval '12 hours'
                                         AND mod(extract(epoch FROM (ends_at - starts_at)), 300) = 0),
  CONSTRAINT ck_appt_notes        CHECK (notes IS NULL OR char_length(btrim(notes)) >= 1),
  CONSTRAINT fk_appt_patient      FOREIGN KEY (patient_id, clinic_id)
    REFERENCES core.patients (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_appt_created_by   FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_appt_id_patient_clinic UNIQUE (id, patient_id, clinic_id),
  -- Intervalo semiaberto [início, fim): fim = início do próximo não conflita. CANCELED e NO_SHOW liberam o horário.
  -- Predicado com <> (e não IN): o pg_dump reinterpreta varchar IN (...) e o guarda de catálogo da restauração acusaria diferença.
  CONSTRAINT ex_appt_no_overlap EXCLUDE USING gist (
    clinic_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
    WHERE (status <> 'CANCELED' AND status <> 'NO_SHOW')
);

-- Agenda do dia/semana (o recuo de 12 h em starts_at vale pelo teto de duração de ck_appt_duration)
CREATE INDEX ix_appt_clinic_start ON core.appointments (clinic_id, starts_at, id);
-- Histórico do paciente (keyset) e índice da FK composta do paciente
CREATE INDEX ix_appt_patient ON core.appointments (clinic_id, patient_id, starts_at DESC, id DESC);

CREATE TRIGGER trg_appt_set_updated_at
  BEFORE UPDATE ON core.appointments
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_appt_forbid_immutable
  BEFORE UPDATE ON core.appointments
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id', 'clinic_id', 'patient_id', 'created_by', 'created_at');

-- Paciente inativo ou anonimizado não recebe agendamento NOVO. Só roda quando o agendamento PASSA a ocupar horário
-- (INSERT ocupante, ou UPDATE saindo de CANCELED/NO_SHOW): o JPA reenvia todas as colunas em todo UPDATE, e assim
-- nada é consultado nem travado à toa. Agendamentos já existentes de quem for inativado seguem como estão.
-- SECURITY INVOKER: lê patients sob a RLS do chamador. FOR KEY SHARE trava a linha do paciente: se uma anonimização
-- está em curso (FOR UPDATE), este INSERT espera o commit dela e relê o paciente já anonimizado (mesmo padrão da V013).
CREATE FUNCTION core.guard_appointment_patient() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_active     boolean;
  v_anonymized timestamptz;
BEGIN
  IF NEW.status = 'CANCELED' OR NEW.status = 'NO_SHOW' THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'UPDATE' AND NOT (OLD.status = 'CANCELED' OR OLD.status = 'NO_SHOW') THEN
    RETURN NEW;
  END IF;
  SELECT p.active, p.anonymized_at INTO v_active, v_anonymized
    FROM core.patients p
   WHERE p.id = NEW.patient_id AND p.clinic_id = NEW.clinic_id
     FOR KEY SHARE;
  -- paciente inexistente ou de outra clínica: nada a dizer aqui; a FK composta responde com 23503
  IF v_anonymized IS NOT NULL THEN
    RAISE EXCEPTION 'paciente anonimizado' USING ERRCODE = 'CR003';
  END IF;
  IF v_active IS FALSE THEN
    RAISE EXCEPTION 'paciente inativo' USING ERRCODE = 'CR005';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION core.guard_appointment_patient() FROM PUBLIC;

CREATE TRIGGER trg_appt_guard_patient
  BEFORE INSERT OR UPDATE ON core.appointments
  FOR EACH ROW EXECUTE FUNCTION core.guard_appointment_patient();

ALTER TABLE core.appointments ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.appointments FORCE  ROW LEVEL SECURITY;

-- Papéis como OR de igualdades (e não IN): ver a nota da V008. Funções de sessão como (SELECT ...): avaliadas uma vez
-- por consulta (InitPlan) e não uma vez por linha.
CREATE POLICY appt_sel ON core.appointments
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));

-- A recepção só cria agendamento novo (SCHEDULED); o dentista cria em qualquer status
CREATE POLICY appt_ins ON core.appointments
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND created_by = core.current_user_id()
              AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN'
                   OR ((SELECT core.current_app_role()) = 'RECEPTIONIST' AND status = 'SCHEDULED')));

-- A recepção só enxerga para alterar as linhas SCHEDULED/CONFIRMED e só grava SCHEDULED/CONFIRMED/CANCELED/NO_SHOW:
-- não toca em IN_PROGRESS nem COMPLETED, nem reabre CANCELED/NO_SHOW. O dentista altera tudo.
CREATE POLICY appt_upd ON core.appointments
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN'
              OR ((SELECT core.current_app_role()) = 'RECEPTIONIST' AND (status = 'SCHEDULED' OR status = 'CONFIRMED'))))
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN'
                   OR ((SELECT core.current_app_role()) = 'RECEPTIONIST'
                       AND (status = 'SCHEDULED' OR status = 'CONFIRMED' OR status = 'CANCELED' OR status = 'NO_SHOW'))));

CREATE POLICY appt_backup_sel ON core.appointments
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE: cancelar é status. As políticas e grants do crm_definer (anonimização) entram na V021.
GRANT SELECT, INSERT, UPDATE ON core.appointments TO crm_app;
GRANT SELECT                 ON core.appointments TO crm_backup;
