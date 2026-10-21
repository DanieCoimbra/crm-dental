-- =====================================================================
-- V013__core_patient_anonymization.sql | Fase 1 / F06-patient-anonymization
-- Objetivo : retenção e anonimização de paciente (LGPD): políticas e grants do crm_definer
--            ESCOPADOS AO TENANT, bloqueios do estado terminal (paciente anonimizado) e a função
--            core.anonymize_patient. O fim da retenção é CALCULADO na função (último registro
--            clínico + core.clinical_retention_period()), sem coluna que possa ficar velha.
-- Executar : scripts/apply_migrations.sh, como crm_owner, numa única transação (crm_owner é membro de crm_definer).
-- Reverter : database/rollback/U013__core_patient_anonymization.sql
-- Depende  : V008–V011 (tabelas), V012 (auditoria), V007 (funções de sessão e retenção).
-- Erros    : 42501 papel ou gravação indevida; 28000 sessão sem clínica/usuário; P0002 paciente
--            inexistente na clínica; CR001 paciente ativo; CR002 retenção não vencida (DETAIL traz a
--            data); CR003 paciente já anonimizado.
-- Segurança: SECURITY DEFINER com search_path fixo, sem SQL dinâmico; crm_definer sem login e sem
--            BYPASSRLS; nenhum USING (true): todas as políticas do definer exigem clinic_id = tenant.
-- =====================================================================

-- ALTER FUNCTION ... OWNER TO exige CREATE no schema para o novo dono (temporário, revogado ao fim)
GRANT CREATE ON SCHEMA core TO crm_definer;

-- ---------------------------------------------------------------------
-- Privilégios mínimos e políticas do crm_definer, escopadas ao tenant da sessão
-- ---------------------------------------------------------------------
GRANT SELECT, UPDATE ON core.patients      TO crm_definer;
GRANT SELECT, UPDATE ON core.patient_files TO crm_definer;
GRANT SELECT, DELETE ON core.medical_histories TO crm_definer;
GRANT SELECT, DELETE ON core.clinical_notes    TO crm_definer;

CREATE POLICY patients_definer_sel ON core.patients
  FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY patients_definer_upd ON core.patients
  FOR UPDATE TO crm_definer
  USING (clinic_id = core.current_clinic_id())
  WITH CHECK (clinic_id = core.current_clinic_id());

CREATE POLICY files_definer_sel ON core.patient_files
  FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY files_definer_upd ON core.patient_files
  FOR UPDATE TO crm_definer
  USING (clinic_id = core.current_clinic_id())
  WITH CHECK (clinic_id = core.current_clinic_id());

CREATE POLICY histories_definer_sel ON core.medical_histories
  FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY histories_definer_del ON core.medical_histories
  FOR DELETE TO crm_definer
  USING (clinic_id = core.current_clinic_id());

CREATE POLICY notes_definer_sel ON core.clinical_notes
  FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY notes_definer_del ON core.clinical_notes
  FOR DELETE TO crm_definer
  USING (clinic_id = core.current_clinic_id());

-- ---------------------------------------------------------------------
-- Guards do estado terminal
-- ---------------------------------------------------------------------
-- Paciente anonimizado não é editado nem reativado; anonymized_at só muda dentro da função de
-- anonimização (flag local core.anonymizing, segundo cinto além do grant por coluna).
CREATE FUNCTION core.guard_anonymized_patient() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog
AS $$
BEGIN
  IF OLD.anonymized_at IS NOT NULL THEN
    RAISE EXCEPTION 'paciente anonimizado' USING ERRCODE = 'CR003';
  END IF;
  IF NEW.anonymized_at IS NOT NULL
     AND COALESCE(current_setting('core.anonymizing', true), '') <> 'on' THEN
    RAISE EXCEPTION 'anonimizacao so pela funcao core.anonymize_patient' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_patients_guard_anonymized
  BEFORE UPDATE ON core.patients
  FOR EACH ROW EXECUTE FUNCTION core.guard_anonymized_patient();

-- Nenhum registro clínico novo para paciente anonimizado (SECURITY INVOKER: lê patients sob a RLS do chamador).
-- FOR KEY SHARE trava a linha do paciente: se uma anonimização está em curso (FOR UPDATE), este INSERT ESPERA
-- o commit dela e, em READ COMMITTED, relê a versão já anonimizada. Sem o lock, um SELECT simples veria o
-- paciente ainda não anonimizado e o registro entraria DEPOIS de o prontuário ter sido apagado.
CREATE FUNCTION core.forbid_child_of_anonymized() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_anonymized timestamptz;
BEGIN
  SELECT p.anonymized_at INTO v_anonymized
    FROM core.patients p
   WHERE p.id = NEW.patient_id AND p.clinic_id = NEW.clinic_id
     FOR KEY SHARE;
  IF v_anonymized IS NOT NULL THEN
    RAISE EXCEPTION 'paciente anonimizado' USING ERRCODE = 'CR003';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_histories_no_anonymized_parent
  BEFORE INSERT ON core.medical_histories
  FOR EACH ROW EXECUTE FUNCTION core.forbid_child_of_anonymized();
CREATE TRIGGER trg_notes_no_anonymized_parent
  BEFORE INSERT ON core.clinical_notes
  FOR EACH ROW EXECUTE FUNCTION core.forbid_child_of_anonymized();
CREATE TRIGGER trg_files_no_anonymized_parent
  BEFORE INSERT ON core.patient_files
  FOR EACH ROW EXECUTE FUNCTION core.forbid_child_of_anonymized();

-- ---------------------------------------------------------------------
-- Anonimização
-- ---------------------------------------------------------------------
CREATE FUNCTION core.anonymize_patient(p_patient_id uuid) RETURNS timestamptz
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

  -- A política do definer só enxerga a clínica da sessão: paciente de outra clínica = inexistente
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

  -- Retenção: último registro clínico + prazo. Sem registro clínico, não há retenção.
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

  -- Prontuário apagado (cada DELETE é auditado pelos triggers da V012)
  DELETE FROM core.medical_histories WHERE patient_id = p_patient_id;
  DELETE FROM core.clinical_notes    WHERE patient_id = p_patient_id;

  -- Exames: marcados como removidos; storage_key (uuid, sem dado pessoal) fica para o backend limpar o Storage
  UPDATE core.patient_files
     SET deleted_at = COALESCE(deleted_at, v_now), original_filename = 'anonimizado'
   WHERE patient_id = p_patient_id;

  -- Dados pessoais substituídos; data de nascimento reduzida ao ano
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

-- ---------------------------------------------------------------------
-- EXECUTE, dono e fechamento da concessão temporária
-- ---------------------------------------------------------------------
REVOKE EXECUTE ON FUNCTION core.anonymize_patient(uuid)        FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.guard_anonymized_patient()     FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.forbid_child_of_anonymized()   FROM PUBLIC;

GRANT EXECUTE ON FUNCTION core.anonymize_patient(uuid) TO crm_app;

ALTER FUNCTION core.anonymize_patient(uuid) OWNER TO crm_definer;

REVOKE CREATE ON SCHEMA core FROM crm_definer;
