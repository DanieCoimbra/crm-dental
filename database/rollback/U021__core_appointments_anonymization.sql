-- =====================================================================
-- U021__core_appointments_anonymization.sql | reversão MANUAL de V021 (nunca automática)
-- Executar : como crm_owner (membro de crm_definer SEM INHERIT: a troca da função roda como crm_definer), por decisão humana,
--            numa transação, ANTES de U015. Restaura a função da V013 (sem CR004 e sem a limpeza de appointments.notes) e
--            remove os grants e as políticas do crm_definer em appointments. Notes já zeradas NÃO voltam.
-- =====================================================================
BEGIN;

GRANT CREATE ON SCHEMA core TO crm_definer;
SET LOCAL ROLE crm_definer;

CREATE OR REPLACE FUNCTION core.anonymize_patient(p_patient_id uuid) RETURNS timestamptz
  LANGUAGE plpgsql VOLATILE SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_clinic     uuid;
  v_active     boolean;
  v_anonymized timestamptz;
  v_last       timestamptz;
  v_until      timestamptz;
  v_now        timestamptz := pg_catalog.now();
BEGIN
  IF core.current_app_role() IS DISTINCT FROM 'DENTIST_ADMIN' THEN
    RAISE EXCEPTION 'apenas o dentista anonimiza' USING ERRCODE = '42501';
  END IF;
  IF core.current_clinic_id() IS NULL OR core.current_user_id() IS NULL THEN
    RAISE EXCEPTION 'sessao sem clinica ou usuario' USING ERRCODE = '28000';
  END IF;

  SELECT p.clinic_id, p.active, p.anonymized_at
    INTO v_clinic, v_active, v_anonymized
    FROM core.patients p
   WHERE p.id = p_patient_id
     FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'paciente inexistente' USING ERRCODE = 'P0002';
  END IF;
  IF v_anonymized IS NOT NULL THEN
    RAISE EXCEPTION 'paciente ja anonimizado' USING ERRCODE = 'CR003';
  END IF;
  IF v_active THEN
    RAISE EXCEPTION 'inative o paciente antes de anonimizar' USING ERRCODE = 'CR001';
  END IF;

  SELECT GREATEST(
           (SELECT max(h.updated_at) FROM core.medical_histories h WHERE h.patient_id = p_patient_id),
           (SELECT max(GREATEST(n.updated_at, n.noted_at)) FROM core.clinical_notes n WHERE n.patient_id = p_patient_id),
           (SELECT max(f.updated_at) FROM core.patient_files f WHERE f.patient_id = p_patient_id))
    INTO v_last;
  IF v_last IS NOT NULL THEN
    v_until := v_last + core.clinical_retention_period();
    IF v_now < v_until THEN
      RAISE EXCEPTION 'retencao do prontuario nao vencida' USING ERRCODE = 'CR002',
        DETAIL = 'liberado em ' || (v_until::date)::text;
    END IF;
  END IF;

  PERFORM set_config('core.anonymizing', 'on', true);

  DELETE FROM core.medical_histories WHERE patient_id = p_patient_id;
  DELETE FROM core.clinical_notes    WHERE patient_id = p_patient_id;

  UPDATE core.patient_files
     SET deleted_at = COALESCE(deleted_at, v_now), original_filename = 'anonimizado'
   WHERE patient_id = p_patient_id;

  UPDATE core.patients
     SET name = 'Paciente anonimizado', cpf = NULL,
         birth_date = pg_catalog.make_date(pg_catalog.date_part('year', birth_date)::int, 1, 1),
         sex = 'NOT_INFORMED', profession = NULL, phone = '0000000000', email = NULL, address = NULL,
         insurance_name = NULL, insurance_number = NULL,
         acquaintance_name = NULL, acquaintance_phone = NULL,
         anonymized_at = v_now, version = version + 1
   WHERE id = p_patient_id;

  INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action)
  VALUES (v_clinic, core.current_user_id(), 'patients', p_patient_id, 'ANONYMIZE');

  PERFORM set_config('core.anonymizing', 'off', true);
  RETURN v_now;
END;
$$;

RESET ROLE;
REVOKE CREATE ON SCHEMA core FROM crm_definer;

DROP POLICY IF EXISTS appt_definer_upd ON core.appointments;
DROP POLICY IF EXISTS appt_definer_sel ON core.appointments;
REVOKE UPDATE (notes, version) ON core.appointments FROM crm_definer;
REVOKE SELECT                  ON core.appointments FROM crm_definer;
COMMIT;
