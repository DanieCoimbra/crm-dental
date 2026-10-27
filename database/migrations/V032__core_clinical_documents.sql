-- =====================================================================
-- V032__core_clinical_documents.sql | Fase 4 / F02-clinical-documents
-- Objetivo : documentos clínicos emitidos (atestado, receita, encaminhamento): registro IMUTÁVEL, com texto livre cifrado pela
--            aplicação (AES-256-GCM em Base64), campos estruturados mínimos por tipo, número por clínica e tipo atribuído pelo banco,
--            anulação (uma vez) e RLS por clínica E por papel (só DENTIST_ADMIN). O PDF não é guardado: o backend o gera sob demanda.
-- Executar : scripts/apply_migrations.sh, como crm_owner, numa única transação.
-- Reverter : database/rollback/U032__core_clinical_documents.sql
-- Depende  : V004 (users), V008 (patients), V015 (appointments e ux_appt_id_patient_clinic), V031 (cro_number), V007 (sessão).
-- Erros    : 42501 recepção gravando, created_by alheio, UPDATE fora do grant, DELETE/TRUNCATE; CR012 emissor sem CRO; CR003 paciente
--            anonimizado; CR005 paciente inativo; 23514 CHECK, anular de novo ou mudar campo imutável; 23503 FK composta;
--            28000 auditoria sem app.user_id (V033).
-- Observações:
--   * A auditoria (trg_cdoc_audit) e as políticas/grants do crm_definer entram nas V033 e V034.
--   * O estado anonimizado já é aceito por ck_cdoc_content (conteúdo e estruturados nulos), mas só a V034 o produz.
--   * Numeração: pg_advisory_xact_lock por (clinic_id, tipo) + max()+1; buracos só em ROLLBACK. crm_app consegue tomar o lock de qualquer
--     clínica (pg_advisory_xact_lock é público): negação de serviço aceita (projeto de portfólio), igual à numeração do orçamento.
--   * created_by e voided_by sem índice próprio: users nunca é apagado (RESTRICT não é avaliado).
-- =====================================================================

CREATE TABLE core.clinical_documents (
  id               uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id        uuid           NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  patient_id       uuid           NOT NULL,
  appointment_id   uuid,
  document_type    varchar(15)    NOT NULL,
  number           integer        NOT NULL,
  issued_on        date           NOT NULL,
  rest_days        smallint,
  target_specialty varchar(60),
  content          varchar(22000),
  sha256           varchar(64)    NOT NULL,
  voided_at        timestamptz,
  voided_by        uuid,
  void_reason      varchar(200),
  anonymized_at    timestamptz,
  created_by       uuid           NOT NULL,
  created_at       timestamptz    NOT NULL DEFAULT now(),
  updated_at       timestamptz    NOT NULL DEFAULT now(),
  version          bigint         NOT NULL DEFAULT 0,
  CONSTRAINT ck_cdoc_type     CHECK (document_type = 'CERTIFICATE' OR document_type = 'PRESCRIPTION' OR document_type = 'REFERRAL'),
  CONSTRAINT ck_cdoc_number   CHECK (number >= 1),
  CONSTRAINT ck_cdoc_sha256   CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_cdoc_rest     CHECK (rest_days IS NULL OR (document_type = 'CERTIFICATE' AND rest_days BETWEEN 1 AND 90)),
  CONSTRAINT ck_cdoc_special  CHECK (target_specialty IS NULL
                                     OR (document_type = 'REFERRAL' AND char_length(btrim(target_specialty)) >= 2)),
  -- campos obrigatórios por tipo (dispensados depois de anonimizar)
  CONSTRAINT ck_cdoc_required CHECK (anonymized_at IS NOT NULL
                                     OR ((document_type <> 'CERTIFICATE' OR rest_days IS NOT NULL)
                                         AND (document_type <> 'REFERRAL' OR target_specialty IS NOT NULL))),
  -- conteúdo: Base64 do envelope AES-GCM; nulo (com os estruturados e o motivo) só depois de anonimizado
  CONSTRAINT ck_cdoc_content  CHECK (
       (anonymized_at IS NULL AND content IS NOT NULL AND char_length(content) >= 40 AND content ~ '^[A-Za-z0-9+/]+={0,2}$')
    OR (anonymized_at IS NOT NULL AND content IS NULL AND rest_days IS NULL AND target_specialty IS NULL AND void_reason IS NULL)),
  CONSTRAINT ck_cdoc_void     CHECK (
       (voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL)
    OR (voided_at IS NOT NULL AND voided_by IS NOT NULL AND (void_reason IS NULL OR char_length(btrim(void_reason)) >= 1))),
  CONSTRAINT fk_cdoc_patient     FOREIGN KEY (patient_id, clinic_id)
    REFERENCES core.patients (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_cdoc_appointment FOREIGN KEY (appointment_id, patient_id, clinic_id)
    REFERENCES core.appointments (id, patient_id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_cdoc_created_by  FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_cdoc_voided_by   FOREIGN KEY (voided_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_cdoc_number      UNIQUE (clinic_id, document_type, number)
);

-- Lista do paciente (keyset por created_at, id); também serve à FK composta do paciente e à anonimização
CREATE INDEX ix_cdoc_patient     ON core.clinical_documents (clinic_id, patient_id, created_at DESC, id DESC);
-- FK composta da consulta
CREATE INDEX ix_cdoc_appointment ON core.clinical_documents (clinic_id, appointment_id) WHERE appointment_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- Funções de trigger
-- ---------------------------------------------------------------------
-- Emissão: papel, guardas do paciente, CRO do emissor, número e campos de sistema
CREATE FUNCTION core.clinical_documents_before_insert() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_active     boolean;
  v_anonymized timestamptz;
  v_cro        varchar(15);
BEGIN
  -- A negativa de papel vem ANTES de qualquer outra checagem: a recepção recebe 42501, nunca CR012
  IF core.current_app_role() IS DISTINCT FROM 'DENTIST_ADMIN' THEN
    RAISE EXCEPTION 'apenas o dentista emite documento' USING ERRCODE = '42501';
  END IF;

  -- FOR KEY SHARE: se uma anonimização está em curso (FOR UPDATE), este INSERT espera o commit e relê o paciente já anonimizado.
  -- Sem linha: segue; a FK composta responde 23503.
  SELECT p.active, p.anonymized_at INTO v_active, v_anonymized
    FROM core.patients p
   WHERE p.id = NEW.patient_id AND p.clinic_id = NEW.clinic_id
     FOR KEY SHARE;
  IF v_anonymized IS NOT NULL THEN
    RAISE EXCEPTION 'paciente anonimizado' USING ERRCODE = 'CR003';
  END IF;
  IF v_active IS FALSE THEN
    RAISE EXCEPTION 'paciente inativo' USING ERRCODE = 'CR005';
  END IF;

  -- Emissor sem CRO e UF. Sem linha de usuário: segue; a FK composta responde 23503.
  SELECT u.cro_number INTO v_cro
    FROM core.users u
   WHERE u.id = NEW.created_by AND u.clinic_id = NEW.clinic_id;
  IF FOUND AND v_cro IS NULL THEN
    RAISE EXCEPTION 'emissor sem CRO e UF cadastrados' USING ERRCODE = 'CR012';
  END IF;

  -- Número por clínica e tipo: serializa a emissão até o COMMIT; o max enxerga o documento confirmado pela transação anterior
  -- (READ COMMITTED: novo snapshot a cada comando). O valor enviado pelo cliente é sempre sobrescrito.
  PERFORM pg_advisory_xact_lock(hashtextextended('cdoc:' || NEW.clinic_id::text || ':' || NEW.document_type, 0));
  NEW.number := COALESCE((SELECT max(d.number) FROM core.clinical_documents d
                           WHERE d.clinic_id = NEW.clinic_id AND d.document_type = NEW.document_type), 0) + 1;

  -- O documento nasce válido e identificado
  NEW.voided_at := NULL;
  NEW.voided_by := NULL;
  NEW.void_reason := NULL;
  NEW.anonymized_at := NULL;
  RETURN NEW;
END;
$$;

-- UPDATE: só duas transições. (1) anular (dentista); (2) anonimizar (crm_definer DENTRO da função, com a flag local: o GUC sozinho é
-- forjável por qualquer sessão, então exige também current_user = 'crm_definer').
CREATE FUNCTION core.guard_clinical_document_update() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  -- (2) anonimização
  IF current_user = 'crm_definer'
     AND COALESCE(pg_catalog.current_setting('core.anonymizing', true), '') = 'on'
     AND OLD.anonymized_at IS NULL AND NEW.anonymized_at IS NOT NULL
     AND NEW.content IS NULL AND NEW.rest_days IS NULL AND NEW.target_specialty IS NULL AND NEW.void_reason IS NULL
     AND NEW.voided_at IS NOT DISTINCT FROM OLD.voided_at
     AND NEW.voided_by IS NOT DISTINCT FROM OLD.voided_by THEN
    RETURN NEW;
  END IF;

  -- (1) anulação: de nulo a um instante, uma vez, pelo dentista que anula
  IF OLD.voided_at IS NULL AND NEW.voided_at IS NOT NULL
     AND OLD.anonymized_at IS NULL AND NEW.anonymized_at IS NULL
     AND core.current_app_role() IS NOT DISTINCT FROM 'DENTIST_ADMIN'
     AND NEW.voided_by IS NOT DISTINCT FROM core.current_user_id()
     AND NEW.content IS NOT DISTINCT FROM OLD.content
     AND NEW.rest_days IS NOT DISTINCT FROM OLD.rest_days
     AND NEW.target_specialty IS NOT DISTINCT FROM OLD.target_specialty THEN
    RETURN NEW;
  END IF;

  RAISE EXCEPTION 'documento clinico e imutavel: so pode ser anulado uma vez ou anonimizado' USING ERRCODE = '23514';
END;
$$;

-- Nem o dono, nem o superusuário apagam ou esvaziam (padrão de payments, V027)
CREATE FUNCTION core.clinical_documents_block() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog
AS $$
BEGIN
  RAISE EXCEPTION 'clinical_documents nao admite DELETE nem TRUNCATE' USING ERRCODE = '42501';
END;
$$;

REVOKE EXECUTE ON FUNCTION core.clinical_documents_before_insert() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.guard_clinical_document_update()   FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.clinical_documents_block()         FROM PUBLIC;

CREATE TRIGGER trg_cdoc_before_insert
  BEFORE INSERT ON core.clinical_documents
  FOR EACH ROW EXECUTE FUNCTION core.clinical_documents_before_insert();

CREATE TRIGGER trg_cdoc_forbid_immutable
  BEFORE UPDATE ON core.clinical_documents
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns(
    'id', 'clinic_id', 'document_type', 'number', 'patient_id', 'appointment_id', 'issued_on', 'sha256', 'created_by', 'created_at');

CREATE TRIGGER trg_cdoc_guard_update
  BEFORE UPDATE ON core.clinical_documents
  FOR EACH ROW EXECUTE FUNCTION core.guard_clinical_document_update();

CREATE TRIGGER trg_cdoc_set_updated_at
  BEFORE UPDATE ON core.clinical_documents
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TRIGGER trg_cdoc_no_delete
  BEFORE DELETE ON core.clinical_documents
  FOR EACH ROW EXECUTE FUNCTION core.clinical_documents_block();

CREATE TRIGGER trg_cdoc_no_truncate
  BEFORE TRUNCATE ON core.clinical_documents
  FOR EACH STATEMENT EXECUTE FUNCTION core.clinical_documents_block();

-- ---------------------------------------------------------------------
-- RLS por clínica E por papel: documento clínico é só do dentista
-- ---------------------------------------------------------------------
ALTER TABLE core.clinical_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.clinical_documents FORCE  ROW LEVEL SECURITY;

CREATE POLICY cdoc_sel ON core.clinical_documents
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY cdoc_ins ON core.clinical_documents
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND created_by = core.current_user_id()
              AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

-- A transição permitida (anular) é validada pelo trigger trg_cdoc_guard_update
CREATE POLICY cdoc_upd ON core.clinical_documents
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY cdoc_backup_sel ON core.clinical_documents
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE; UPDATE só das colunas da anulação
GRANT SELECT, INSERT ON core.clinical_documents TO crm_app;
GRANT UPDATE (voided_at, voided_by, void_reason, version) ON core.clinical_documents TO crm_app;
GRANT SELECT ON core.clinical_documents TO crm_backup;
