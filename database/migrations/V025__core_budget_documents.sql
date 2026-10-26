-- =====================================================================
-- V025__core_budget_documents.sql | Fase 3 / F03-budget-documents
-- Objetivo : metadados do PDF do orçamento guardado no Supabase Storage (mesmo bucket privado dos exames, prefixo budgets/).
--            Uma linha por PDF; reenvio = nova revisão (atribuída pelo banco); a linha é imutável salvo deleted_at, que só vai de
--            nulo a um instante. PDF só para orçamento fora de DRAFT. Recepção lê e não grava. Sem DELETE.
-- Executar : scripts/apply_migrations.sh, como crm_owner.
-- Reverter : database/rollback/U025__core_budget_documents.sql
-- Depende  : V024 (budgets e seu UNIQUE (id, patient_id, clinic_id)), V004 (users), V002 (triggers genéricos), V007.
-- Erros    : CR008 orçamento em DRAFT; 23514 CHECK (chave, sha256, tipo, tamanho), coluna imutável ou deleted_at voltando;
--            23505 storage_key repetida; 23503 FK; 42501 papel ou autoria.
-- Observações:
--   * O banco não acessa o Storage: a ordem do backend (objeto primeiro, linha depois) evita linha sem objeto.
--   * O objeto contém o NOME do paciente e não tem RLS: a anonimização (V029) marca deleted_at e o backend apaga os objetos.
--   * Sem version e sem updated_at (decisão S12): crm_app só atualiza deleted_at (grant por coluna).
--   * created_by (FK composta) não tem índice de propósito: users nunca é apagado.
-- =====================================================================

CREATE TABLE core.budget_documents (
  id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id    uuid         NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  budget_id    uuid         NOT NULL,
  patient_id   uuid         NOT NULL,
  revision     smallint     NOT NULL,                          -- atribuída pelo trigger
  storage_key  varchar(120) NOT NULL,
  content_type varchar(20)  NOT NULL,
  size_bytes   bigint       NOT NULL,
  sha256       varchar(64)  NOT NULL,
  generated_at timestamptz  NOT NULL DEFAULT now(),
  deleted_at   timestamptz,
  created_by   uuid         NOT NULL,
  created_at   timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT ck_bdoc_revision CHECK (revision >= 1),
  CONSTRAINT ck_bdoc_type     CHECK (content_type = 'application/pdf'),
  CONSTRAINT ck_bdoc_size     CHECK (size_bytes BETWEEN 1 AND 5242880),
  CONSTRAINT ck_bdoc_sha      CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_bdoc_key      CHECK (storage_key ~ ('^' || clinic_id::text || '/' || patient_id::text || '/budgets/[0-9a-f-]{36}$')),
  CONSTRAINT fk_bdoc_budget     FOREIGN KEY (budget_id, patient_id, clinic_id)
    REFERENCES core.budgets (id, patient_id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_bdoc_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_bdoc_storage_key     UNIQUE (storage_key),
  CONSTRAINT ux_bdoc_budget_revision UNIQUE (budget_id, revision)
);

-- PDF ativo do orçamento = maior revisão com deleted_at nulo
CREATE INDEX ix_bdoc_budget_active ON core.budget_documents (budget_id, revision DESC) WHERE deleted_at IS NULL;
-- Anonimização por paciente
CREATE INDEX ix_bdoc_patient       ON core.budget_documents (clinic_id, patient_id);

-- Trava o orçamento (serializa duas revisões simultâneas), exige que não seja DRAFT e atribui a revisão (conta as removidas:
-- o número não volta). Sem linha visível: devolve NEW e deixa a RLS/FK responderem (S16).
CREATE FUNCTION core.budget_documents_before_insert() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_status varchar(10);
BEGIN
  SELECT b.status INTO v_status
    FROM core.budgets b
   WHERE b.id = NEW.budget_id AND b.clinic_id = NEW.clinic_id
     FOR NO KEY UPDATE;
  IF FOUND AND v_status = 'DRAFT' THEN
    RAISE EXCEPTION 'pdf so depois de enviar o orcamento' USING ERRCODE = 'CR008';
  END IF;
  -- Com o orçamento invisível ou inexistente a revisão também é calculada, sem erro: o NOT NULL não pode responder antes da RLS
  -- (42501) e da FK (23503).
  NEW.revision := COALESCE((SELECT max(d.revision) FROM core.budget_documents d WHERE d.budget_id = NEW.budget_id), 0) + 1;
  RETURN NEW;
END;
$$;

-- deleted_at só vai de nulo a um instante (sem volta, sem mudança depois de preenchido)
CREATE FUNCTION core.guard_budget_document_update() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
    RAISE EXCEPTION 'pdf removido nao volta nem muda' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION core.budget_documents_before_insert() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.guard_budget_document_update()   FROM PUBLIC;

CREATE TRIGGER trg_bdoc_before_insert
  BEFORE INSERT ON core.budget_documents
  FOR EACH ROW EXECUTE FUNCTION core.budget_documents_before_insert();

CREATE TRIGGER trg_bdoc_forbid_immutable
  BEFORE UPDATE ON core.budget_documents
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns(
    'id', 'clinic_id', 'budget_id', 'patient_id', 'revision', 'storage_key', 'content_type', 'size_bytes', 'sha256',
    'generated_at', 'created_by', 'created_at');

CREATE TRIGGER trg_bdoc_guard_update
  BEFORE UPDATE ON core.budget_documents
  FOR EACH ROW EXECUTE FUNCTION core.guard_budget_document_update();

ALTER TABLE core.budget_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.budget_documents FORCE  ROW LEVEL SECURITY;

-- A recepção consulta o PDF (reenviar, imprimir) mas não o registra
CREATE POLICY bdoc_sel ON core.budget_documents
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));

CREATE POLICY bdoc_ins ON core.budget_documents
  FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND created_by = core.current_user_id()
              AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY bdoc_upd ON core.budget_documents
  FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

CREATE POLICY bdoc_backup_sel ON core.budget_documents
  FOR SELECT TO crm_backup
  USING (true);

-- Sem DELETE; UPDATE só da coluna deleted_at
GRANT SELECT, INSERT          ON core.budget_documents TO crm_app;
GRANT UPDATE (deleted_at)     ON core.budget_documents TO crm_app;
GRANT SELECT                  ON core.budget_documents TO crm_backup;
