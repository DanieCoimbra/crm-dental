-- =====================================================================
-- V033__core_clinical_documents_audit.sql | Fase 4 / F03-documents-audit-and-download
-- Objetivo : estende a trilha de auditoria (audit_log) aos documentos clínicos: INSERT, VOID (anulação), ANONYMIZE (uma por documento) e
--            DOWNLOAD (cada download ou impressão do PDF gerado pelo backend), SEM valores (só identificadores). core.write_audit()
--            grava TG_OP e não sabe dizer VOID/ANONYMIZE, então há função de trigger própria; DOWNLOAD não é DML e vem de uma função
--            SECURITY DEFINER mínima chamada pelo backend.
-- Executar : scripts/apply_migrations.sh, como crm_owner (membro de crm_definer), numa única transação.
-- Reverter : database/rollback/U033__core_clinical_documents_audit.sql
-- Depende  : V012 (audit_log, políticas do definer), V028 (lista vigente de ck_audit_table), V032 (clinical_documents).
-- Erros    : 28000 gravação sem app.user_id (a operação inteira reverte); 42501 papel diferente de DENTIST_ADMIN no download;
--            P0002 documento inexistente ou de outra clínica; CR003 documento anonimizado; 23514 UPDATE fora das duas transições.
-- Observações:
--   * DROP/ADD CONSTRAINT valida as linhas existentes, mas não executa os triggers de linha de audit_log: o log continua só de inserção.
--   * Funções criadas por crm_owner (CREATE TRIGGER exige EXECUTE do criador) e entregues ao crm_definer ao fim (padrão V012/V013).
--   * O crm_definer ganha só o mínimo para a função de download: SELECT (id, clinic_id, anonymized_at) e a política de leitura por
--     tenant. A V034 amplia (SELECT completo e UPDATE da anonimização).
-- =====================================================================

GRANT CREATE ON SCHEMA core TO crm_definer;

ALTER TABLE core.audit_log DROP CONSTRAINT ck_audit_table;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_table CHECK (
  table_name IN ('patients', 'medical_histories', 'clinical_notes', 'patient_files', 'appointments',
                 'budgets', 'installments', 'payments', 'budget_documents', 'clinical_documents'));
ALTER TABLE core.audit_log DROP CONSTRAINT ck_audit_action;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_action CHECK (
  action IN ('INSERT', 'UPDATE', 'DELETE', 'ANONYMIZE', 'VOID', 'DOWNLOAD'));

-- Leitura mínima do crm_definer, escopada ao tenant (usada por record_document_download)
GRANT SELECT (id, clinic_id, anonymized_at) ON core.clinical_documents TO crm_definer;
CREATE POLICY cdoc_definer_sel ON core.clinical_documents
  FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());

-- Trigger: INSERT -> INSERT; UPDATE de anulação -> VOID; UPDATE de anonimização -> ANONYMIZE; qualquer outro UPDATE é impossível
-- pela guarda da V032 e falha aqui também. Grava só identificadores; app.user_id é obrigatório (auditoria não é anônima).
CREATE FUNCTION core.write_document_audit() RETURNS trigger
  LANGUAGE plpgsql VOLATILE SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_user   uuid := NULLIF(pg_catalog.current_setting('app.user_id', true), '')::uuid;
  v_action varchar(10);
BEGIN
  IF v_user IS NULL THEN
    RAISE EXCEPTION 'auditoria exige app.user_id' USING ERRCODE = '28000';
  END IF;
  IF TG_OP = 'INSERT' THEN
    v_action := 'INSERT';
  ELSIF OLD.voided_at IS NULL AND NEW.voided_at IS NOT NULL THEN
    v_action := 'VOID';
  ELSIF OLD.anonymized_at IS NULL AND NEW.anonymized_at IS NOT NULL THEN
    v_action := 'ANONYMIZE';
  ELSE
    RAISE EXCEPTION 'atualizacao de documento fora da anulacao e da anonimizacao' USING ERRCODE = '23514';
  END IF;
  INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action)
  VALUES (NEW.clinic_id, v_user, 'clinical_documents', NEW.id, v_action);
  RETURN NULL;
END;
$$;

-- DOWNLOAD: chamada pelo backend na mesma transação que gera o PDF (download ou impressão). Cada chamada é um evento.
CREATE FUNCTION core.record_document_download(p_document_id uuid) RETURNS void
  LANGUAGE plpgsql VOLATILE SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_anonymized timestamptz;
BEGIN
  IF core.current_app_role() IS DISTINCT FROM 'DENTIST_ADMIN' THEN
    RAISE EXCEPTION 'apenas o dentista baixa documento' USING ERRCODE = '42501';
  END IF;
  IF core.current_clinic_id() IS NULL OR core.current_user_id() IS NULL THEN
    RAISE EXCEPTION 'sessao sem clinica ou usuario' USING ERRCODE = '28000';
  END IF;

  -- A política do definer só enxerga a clínica da sessão: documento de outra clínica = inexistente
  SELECT d.anonymized_at INTO v_anonymized
    FROM core.clinical_documents d
   WHERE d.id = p_document_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'documento inexistente' USING ERRCODE = 'P0002';
  END IF;
  IF v_anonymized IS NOT NULL THEN
    RAISE EXCEPTION 'documento anonimizado' USING ERRCODE = 'CR003';
  END IF;

  INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action)
  VALUES (core.current_clinic_id(), core.current_user_id(), 'clinical_documents', p_document_id, 'DOWNLOAD');
END;
$$;

REVOKE EXECUTE ON FUNCTION core.write_document_audit()            FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.record_document_download(uuid)    FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION core.record_document_download(uuid)    TO crm_app;

-- O trigger vem ANTES da troca de dono: CREATE TRIGGER exige EXECUTE do criador (crm_owner)
CREATE TRIGGER trg_cdoc_audit
  AFTER INSERT OR UPDATE ON core.clinical_documents
  FOR EACH ROW EXECUTE FUNCTION core.write_document_audit();

ALTER FUNCTION core.write_document_audit()         OWNER TO crm_definer;
ALTER FUNCTION core.record_document_download(uuid) OWNER TO crm_definer;

REVOKE CREATE ON SCHEMA core FROM crm_definer;
