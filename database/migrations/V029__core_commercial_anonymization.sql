-- =====================================================================
-- V029__core_commercial_anonymization.sql | Fase 3 / F07-commercial-anonymization
-- Objetivo : ajusta a anonimização de paciente (LGPD) ao comercial e ao financeiro: o crm_definer ganha o mínimo de privilégio,
--            escopado ao tenant (SELECT e UPDATE (notes, version) em budgets; SELECT em installments; SELECT e UPDATE (deleted_at)
--            em budget_documents); core.anonymize_patient passa a (1) recusar com CR006 quando há parcela OPEN do paciente,
--            (2) zerar budgets.notes e (3) marcar os PDFs do paciente como removidos (deleted_at; o backend apaga os objetos).
--            Orçamentos, itens, parcelas e pagamentos PERMANECEM (obrigação contábil; só guardam patient_id).
-- Executar : scripts/apply_migrations.sh, como crm_owner, numa única transação (crm_owner é membro de crm_definer).
-- Reverter : database/rollback/U029__core_commercial_anonymization.sql (restaura a função da V021)
-- Depende  : V021 (anonymize_patient), V024 (budgets), V025 (budget_documents), V026 (installments), V028 (auditoria).
-- Erros    : os da V021 (42501, 28000, P0002, CR001, CR002, CR003, CR004) e CR006 parcela OPEN do paciente.
--            Precedência: CR002 -> CR004 -> CR006.
-- Segurança: SECURITY DEFINER com search_path fixo, sem SQL dinâmico; crm_definer sem login e sem BYPASSRLS; as políticas do definer
--            exigem clinic_id = tenant (nenhum USING (true)).
-- Observações:
--   * Orçamento e financeiro NÃO entram no cálculo da retenção de 20 anos (não são registro clínico).
--   * A limpeza de budgets.notes roda DEPOIS de ligar a flag core.anonymizing: fora dela o trigger de congelamento negaria (CR007).
--   * crm_owner é membro de crm_definer SEM INHERIT (só SET): a troca da função roda COMO crm_definer (SET LOCAL ROLE), que precisa de
--     CREATE em core só durante a troca (padrão temporário da V013/V021).
-- =====================================================================

GRANT SELECT                  ON core.budgets          TO crm_definer;
GRANT UPDATE (notes, version) ON core.budgets          TO crm_definer;
GRANT SELECT                  ON core.installments     TO crm_definer;
GRANT SELECT                  ON core.budget_documents TO crm_definer;
GRANT UPDATE (deleted_at)     ON core.budget_documents TO crm_definer;

CREATE POLICY budgets_definer_sel ON core.budgets
  FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY budgets_definer_upd ON core.budgets
  FOR UPDATE TO crm_definer
  USING (clinic_id = core.current_clinic_id())
  WITH CHECK (clinic_id = core.current_clinic_id());

CREATE POLICY inst_definer_sel ON core.installments
  FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());

CREATE POLICY bdoc_definer_sel ON core.budget_documents
  FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY bdoc_definer_upd ON core.budget_documents
  FOR UPDATE TO crm_definer
  USING (clinic_id = core.current_clinic_id())
  WITH CHECK (clinic_id = core.current_clinic_id());

-- Substitui a função da V021: dono (crm_definer) e EXECUTE do crm_app permanecem; os trechos novos estão marcados -- NOVO
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

  -- Retenção: último registro clínico + prazo. Sem registro clínico, não há retenção. Agendamento, orçamento e financeiro NÃO são
  -- registro clínico.
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

  -- Agendamento futuro em aberto impede a anonimização (senão um horário ficaria ocupado por "Paciente anonimizado")
  IF EXISTS (SELECT 1 FROM core.appointments a
              WHERE a.patient_id = p_patient_id
                AND (a.status = 'SCHEDULED' OR a.status = 'CONFIRMED' OR a.status = 'IN_PROGRESS')
                AND a.ends_at > v_now) THEN
    RAISE EXCEPTION 'cancele os agendamentos futuros antes de anonimizar' USING ERRCODE = 'CR004';
  END IF;

  -- NOVO: parcela em aberto (saldo a receber) impede a anonimização; orçamento DRAFT/SENT sem parcelas não bloqueia
  IF EXISTS (SELECT 1 FROM core.installments i
              WHERE i.clinic_id = v_clinic AND i.patient_id = p_patient_id AND i.status = 'OPEN') THEN
    RAISE EXCEPTION 'quite ou cancele as parcelas em aberto antes de anonimizar' USING ERRCODE = 'CR006';
  END IF;

  PERFORM set_config('core.anonymizing', 'on', true);

  -- Prontuário apagado (cada DELETE é auditado pelos triggers da V012)
  DELETE FROM core.medical_histories WHERE patient_id = p_patient_id;
  DELETE FROM core.clinical_notes    WHERE patient_id = p_patient_id;

  -- Exames: marcados como removidos; storage_key (uuid, sem dado pessoal) fica para o backend limpar o Storage
  UPDATE core.patient_files
     SET deleted_at = COALESCE(deleted_at, v_now), original_filename = 'anonimizado'
   WHERE patient_id = p_patient_id;

  -- Texto livre da agenda (pode citar nome ou queixa); horário, status e paciente permanecem
  UPDATE core.appointments
     SET notes = NULL, version = version + 1
   WHERE patient_id = p_patient_id AND notes IS NOT NULL;

  -- NOVO: texto livre do orçamento (vai no PDF); número, total, status, itens, parcelas e pagamentos permanecem
  UPDATE core.budgets
     SET notes = NULL, version = version + 1
   WHERE patient_id = p_patient_id AND notes IS NOT NULL;

  -- NOVO: o PDF do orçamento traz o nome do paciente; marcado como removido (o backend apaga os objetos do Storage)
  UPDATE core.budget_documents
     SET deleted_at = COALESCE(deleted_at, v_now)
   WHERE clinic_id = v_clinic AND patient_id = p_patient_id;

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

RESET ROLE;
REVOKE CREATE ON SCHEMA core FROM crm_definer;
