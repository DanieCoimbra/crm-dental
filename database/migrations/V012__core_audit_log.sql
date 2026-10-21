-- =====================================================================
-- V012__core_audit_log.sql | Fase 1 / F05-audit-trail
-- Objetivo : trilha de auditoria das tabelas clínicas (patients, medical_histories, clinical_notes,
--            patient_files): quem, quando, qual tabela, qual registro, qual ação. SEM valores de
--            coluna e sem dado pessoal ou clínico. Só de inserção, inclusive para o dono.
-- Executar : scripts/apply_migrations.sh, como crm_owner, numa única transação (crm_owner é membro de crm_definer).
-- Reverter : database/rollback/U012__core_audit_log.sql
-- Depende  : V008–V011 (tabelas auditadas), V004 (users), V007 (funções de sessão).
-- Segurança: core.write_audit() é SECURITY DEFINER do crm_definer (sem login, sem BYPASSRLS), com
--            search_path fixo e sem SQL dinâmico; a escrita passa pela política escopada ao tenant.
-- =====================================================================

-- ALTER FUNCTION ... OWNER TO exige CREATE no schema para o novo dono (temporário, revogado ao fim)
GRANT CREATE ON SCHEMA core TO crm_definer;

CREATE TABLE core.audit_log (
  id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  clinic_id   uuid         NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  user_id     uuid         NOT NULL,
  table_name  varchar(30)  NOT NULL,
  record_id   uuid         NOT NULL,
  action      varchar(10)  NOT NULL,
  occurred_at timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT ck_audit_table  CHECK (table_name IN ('patients', 'medical_histories', 'clinical_notes', 'patient_files')),
  CONSTRAINT ck_audit_action CHECK (action IN ('INSERT', 'UPDATE', 'DELETE', 'ANONYMIZE')),
  CONSTRAINT fk_audit_user   FOREIGN KEY (user_id, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT
);

-- Consulta recente por clínica e histórico de um registro
CREATE INDEX ix_audit_clinic_time ON core.audit_log (clinic_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_record      ON core.audit_log (clinic_id, table_name, record_id, occurred_at DESC);

ALTER TABLE core.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.audit_log FORCE  ROW LEVEL SECURITY;

-- Leitura: só o dentista da clínica. Sem política de INSERT/UPDATE/DELETE para crm_app (negado).
-- Desempenho: as funções de sessão são chamadas como (SELECT ...): o PostgreSQL as avalia UMA vez por consulta (InitPlan)
-- e não uma vez por linha (funções com SET search_path não são embutidas e custam ~1,5 us por linha).
CREATE POLICY audit_sel ON core.audit_log
  FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');

-- Escrita: só a função de auditoria (crm_definer), para a clínica da sessão
CREATE POLICY audit_definer_ins ON core.audit_log
  FOR INSERT TO crm_definer
  WITH CHECK (clinic_id = core.current_clinic_id());

CREATE POLICY audit_backup_sel ON core.audit_log
  FOR SELECT TO crm_backup
  USING (true);

GRANT SELECT ON core.audit_log TO crm_app, crm_backup;
GRANT INSERT ON core.audit_log TO crm_definer;
-- O pg_dump do crm_backup lê a sequência da identidade (last_value/is_called); sem este GRANT o backup
-- inteiro falha. Só SELECT: o crm_backup não consome nem altera a sequência, e crm_app/crm_definer não
-- precisam de privilégio na sequência para inserir (identidade GENERATED ALWAYS).
GRANT SELECT ON SEQUENCE core.audit_log_id_seq TO crm_backup;

-- Log só cresce: nenhum papel (nem o dono, nem o superusuário) altera ou apaga linhas.
CREATE FUNCTION core.audit_log_append_only() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog
AS $$
BEGIN
  RAISE EXCEPTION 'audit_log e somente de insercao' USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER trg_audit_log_append_only
  BEFORE UPDATE OR DELETE ON core.audit_log
  FOR EACH ROW EXECUTE FUNCTION core.audit_log_append_only();

-- TRUNCATE não dispara trigger de linha: um trigger de comando fecha essa porta (também no TRUNCATE ... CASCADE
-- vindo de tabelas que referenciam). Só quem desliga o trigger (DDL, dono/superusuário) consegue esvaziar o log.
CREATE TRIGGER trg_audit_log_no_truncate
  BEFORE TRUNCATE ON core.audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION core.audit_log_append_only();

-- Trigger de auditoria. Grava só identificadores; app.user_id é obrigatório (auditoria não é anônima).
CREATE FUNCTION core.write_audit() RETURNS trigger
  LANGUAGE plpgsql VOLATILE SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_user uuid := NULLIF(pg_catalog.current_setting('app.user_id', true), '')::uuid;
  v_row  record;
BEGIN
  IF v_user IS NULL THEN
    RAISE EXCEPTION 'auditoria exige app.user_id' USING ERRCODE = '28000';
  END IF;
  IF TG_OP = 'DELETE' THEN
    v_row := OLD;
  ELSE
    v_row := NEW;
  END IF;
  INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action)
  VALUES (v_row.clinic_id, v_user, TG_TABLE_NAME, v_row.id, TG_OP);
  RETURN NULL;
END;
$$;

REVOKE EXECUTE ON FUNCTION core.write_audit()            FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION core.audit_log_append_only()  FROM PUBLIC;

-- Os triggers vêm ANTES da troca de dono: CREATE TRIGGER exige EXECUTE do criador (crm_owner), que só
-- o dono original tem (o REVOKE acima tirou o de PUBLIC). Em tempo de disparo nada disso é checado.
CREATE TRIGGER trg_patients_audit
  AFTER INSERT OR UPDATE OR DELETE ON core.patients
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();
CREATE TRIGGER trg_histories_audit
  AFTER INSERT OR UPDATE OR DELETE ON core.medical_histories
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();
CREATE TRIGGER trg_notes_audit
  AFTER INSERT OR UPDATE OR DELETE ON core.clinical_notes
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();
CREATE TRIGGER trg_files_audit
  AFTER INSERT OR UPDATE OR DELETE ON core.patient_files
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();

ALTER FUNCTION core.write_audit() OWNER TO crm_definer;

REVOKE CREATE ON SCHEMA core FROM crm_definer;
