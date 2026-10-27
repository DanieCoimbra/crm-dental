-- =====================================================================
-- tests/phase4_permissions.sql | Fase 4 / F06-phase4-verification (teste 139)
-- Matriz papel x tabela x operação da Fase 4 (spec global, 2.5), menor privilégio e catálogo:
--   * clinical_documents: crm_app SELECT/INSERT e UPDATE só de (voided_at, voided_by, void_reason, version); sem DELETE, TRUNCATE,
--     REFERENCES, TRIGGER; crm_backup só SELECT; crm_definer SELECT e UPDATE só das 6 colunas da anonimização;
--   * RLS ENABLE + FORCE, 3 políticas do crm_app (sem DELETE), políticas do definer e do backup nomeadas; sem BYPASSRLS;
--   * funções novas: SECURITY DEFINER do crm_definer com search_path fixo; EXECUTE só de quem precisa;
--   * recepção: 0 linhas em documentos, parcelas e pagamentos; lê pacientes, agendamentos e orçamentos (C2);
--   * crm_app não faz DROP, ALTER nem TRUNCATE.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase4_seed.sql

\set d1 'f6000000-0000-4000-8000-000000000051'
\set bP 'f6000000-0000-4000-8000-000000000052'
\set iP 'f6000000-0000-4000-8000-000000000053'
\set pP 'f6000000-0000-4000-8000-000000000054'

-- privilégios de tabela
DO $$
DECLARE
  t constant text := 'core.clinical_documents';
  col text;
BEGIN
  -- crm_app
  IF NOT (has_table_privilege('crm_app', t, 'SELECT') AND has_table_privilege('crm_app', t, 'INSERT')) THEN
    RAISE EXCEPTION 'T139: crm_app deveria ler e inserir documentos';
  END IF;
  IF has_table_privilege('crm_app', t, 'UPDATE') OR has_table_privilege('crm_app', t, 'DELETE') OR has_table_privilege('crm_app', t, 'TRUNCATE')
     OR has_table_privilege('crm_app', t, 'REFERENCES') OR has_table_privilege('crm_app', t, 'TRIGGER') THEN
    RAISE EXCEPTION 'T139: crm_app com privilegio de tabela alem de SELECT e INSERT';
  END IF;
  FOREACH col IN ARRAY ARRAY['voided_at', 'voided_by', 'void_reason', 'version'] LOOP
    IF NOT has_column_privilege('crm_app', t, col, 'UPDATE') THEN RAISE EXCEPTION 'T139: crm_app deveria atualizar %', col; END IF;
  END LOOP;
  FOREACH col IN ARRAY ARRAY['id', 'clinic_id', 'patient_id', 'appointment_id', 'document_type', 'number', 'issued_on', 'rest_days',
                             'target_specialty', 'content', 'sha256', 'anonymized_at', 'created_by', 'created_at', 'updated_at'] LOOP
    IF has_column_privilege('crm_app', t, col, 'UPDATE') THEN RAISE EXCEPTION 'T139: crm_app nao deveria atualizar %', col; END IF;
  END LOOP;
  -- crm_backup
  IF NOT has_table_privilege('crm_backup', t, 'SELECT') THEN RAISE EXCEPTION 'T139: crm_backup deveria ler documentos'; END IF;
  IF has_table_privilege('crm_backup', t, 'INSERT') OR has_table_privilege('crm_backup', t, 'UPDATE')
     OR has_table_privilege('crm_backup', t, 'DELETE') OR has_table_privilege('crm_backup', t, 'TRUNCATE') THEN
    RAISE EXCEPTION 'T139: crm_backup so pode ler';
  END IF;
  -- crm_definer
  IF NOT has_table_privilege('crm_definer', t, 'SELECT') THEN RAISE EXCEPTION 'T139: crm_definer deveria ler documentos'; END IF;
  IF has_table_privilege('crm_definer', t, 'INSERT') OR has_table_privilege('crm_definer', t, 'DELETE')
     OR has_table_privilege('crm_definer', t, 'TRUNCATE') OR has_table_privilege('crm_definer', t, 'UPDATE') THEN
    RAISE EXCEPTION 'T139: crm_definer so atualiza as colunas da anonimizacao';
  END IF;
  FOREACH col IN ARRAY ARRAY['content', 'rest_days', 'target_specialty', 'void_reason', 'anonymized_at', 'version'] LOOP
    IF NOT has_column_privilege('crm_definer', t, col, 'UPDATE') THEN RAISE EXCEPTION 'T139: crm_definer deveria atualizar %', col; END IF;
  END LOOP;
  FOREACH col IN ARRAY ARRAY['sha256', 'number', 'document_type', 'patient_id', 'voided_at', 'voided_by', 'created_by', 'issued_on'] LOOP
    IF has_column_privilege('crm_definer', t, col, 'UPDATE') THEN RAISE EXCEPTION 'T139: crm_definer nao deveria atualizar %', col; END IF;
  END LOOP;
  -- nenhum privilegio para PUBLIC
  IF has_table_privilege('public', t, 'SELECT') THEN RAISE EXCEPTION 'T139: PUBLIC com SELECT em documentos'; END IF;
END $$;

-- RLS e políticas
DO $$
DECLARE c record; n int;
BEGIN
  SELECT relrowsecurity, relforcerowsecurity, relowner::regrole::text AS owner INTO c FROM pg_class WHERE oid = 'core.clinical_documents'::regclass;
  IF NOT c.relrowsecurity OR NOT c.relforcerowsecurity THEN RAISE EXCEPTION 'T139: RLS ENABLE+FORCE ausente em clinical_documents'; END IF;
  IF c.owner <> 'crm_owner' THEN RAISE EXCEPTION 'T139: dono de clinical_documents deveria ser crm_owner (%)', c.owner; END IF;
  SELECT count(*) INTO n FROM pg_policy WHERE polrelid = 'core.clinical_documents'::regclass AND polroles = ARRAY['crm_app'::regrole::oid];
  IF n <> 3 THEN RAISE EXCEPTION 'T139: esperadas 3 politicas do crm_app (sel, ins, upd), ha %', n; END IF;
  IF EXISTS (SELECT 1 FROM pg_policy WHERE polrelid = 'core.clinical_documents'::regclass AND polcmd = 'd') THEN
    RAISE EXCEPTION 'T139: nao deve existir politica de DELETE';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_policy WHERE polrelid = 'core.clinical_documents'::regclass AND polcmd = '*') THEN
    RAISE EXCEPTION 'T139: nao deve existir politica FOR ALL';
  END IF;
  -- políticas do crm_app: USING/WITH CHECK com clinic_id e papel
  IF EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = 'core.clinical_documents'::regclass AND p.polroles = ARRAY['crm_app'::regrole::oid]
               AND (pg_get_expr(COALESCE(p.polqual, p.polwithcheck), p.polrelid) NOT LIKE '%current_clinic_id%'
                    OR pg_get_expr(COALESCE(p.polqual, p.polwithcheck), p.polrelid) NOT LIKE '%DENTIST_ADMIN%')) THEN
    RAISE EXCEPTION 'T139: politica do crm_app sem clinic_id ou sem papel';
  END IF;
  -- definer: todas por tenant, nenhuma USING (true)
  IF EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = 'core.clinical_documents'::regclass AND p.polroles = ARRAY['crm_definer'::regrole::oid]
               AND pg_get_expr(p.polqual, p.polrelid) NOT LIKE '%current_clinic_id%') THEN
    RAISE EXCEPTION 'T139: politica do crm_definer sem escopo de tenant';
  END IF;
  SELECT count(*) INTO n FROM pg_policy WHERE polrelid = 'core.clinical_documents'::regclass AND polroles = ARRAY['crm_definer'::regrole::oid];
  IF n <> 2 THEN RAISE EXCEPTION 'T139: esperadas 2 politicas do crm_definer (sel, upd), ha %', n; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policy WHERE polrelid = 'core.clinical_documents'::regclass AND polname = 'cdoc_backup_sel') THEN
    RAISE EXCEPTION 'T139: cdoc_backup_sel ausente';
  END IF;
  -- papéis sem BYPASSRLS nem superusuário
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('crm_app', 'crm_backup', 'crm_definer', 'crm_owner') AND (rolbypassrls OR rolsuper)) THEN
    RAISE EXCEPTION 'T139: papel do CRM com BYPASSRLS ou superusuario';
  END IF;
END $$;

-- funções novas
DO $$
DECLARE f record;
BEGIN
  FOR f IN SELECT p.proname, p.prosecdef, pg_get_userbyid(p.proowner) AS owner, p.proconfig, p.oid
             FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'core' AND p.proname IN ('write_document_audit', 'record_document_download') LOOP
    IF NOT f.prosecdef THEN RAISE EXCEPTION 'T139: % deveria ser SECURITY DEFINER', f.proname; END IF;
    IF f.owner <> 'crm_definer' THEN RAISE EXCEPTION 'T139: dono de % deveria ser crm_definer (%)', f.proname, f.owner; END IF;
    IF f.proconfig IS NULL OR NOT (f.proconfig @> ARRAY['search_path=pg_catalog, pg_temp']) THEN
      RAISE EXCEPTION 'T139: % sem search_path fixo (%)', f.proname, f.proconfig;
    END IF;
    IF has_function_privilege('public', f.oid, 'EXECUTE') OR has_function_privilege('crm_backup', f.oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'T139: EXECUTE de % aberto a PUBLIC ou crm_backup', f.proname;
    END IF;
  END LOOP;
  IF NOT has_function_privilege('crm_app', 'core.record_document_download(uuid)', 'EXECUTE') THEN
    RAISE EXCEPTION 'T139: crm_app deveria executar record_document_download';
  END IF;
  IF has_function_privilege('crm_app', 'core.write_document_audit()', 'EXECUTE') THEN
    RAISE EXCEPTION 'T139: crm_app nao deve executar a funcao de trigger de auditoria';
  END IF;
  -- funções de trigger de documentos e do CRO: sem EXECUTE para PUBLIC
  IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
              WHERE n.nspname = 'core' AND p.proname IN ('clinical_documents_before_insert', 'guard_clinical_document_update', 'clinical_documents_block', 'guard_user_cro')
                AND has_function_privilege('public', p.oid, 'EXECUTE')) THEN
    RAISE EXCEPTION 'T139: funcao de trigger da Fase 4 executavel por PUBLIC';
  END IF;
  -- crm_definer não ganha nada em users além do que já tinha (só SELECT de colunas de login) e nada nas colunas novas
  IF has_column_privilege('crm_definer', 'core.users', 'cro_number', 'SELECT') OR has_column_privilege('crm_definer', 'core.users', 'cro_number', 'UPDATE') THEN
    RAISE EXCEPTION 'T139: crm_definer nao deve alcancar o CRO';
  END IF;
  IF has_column_privilege('crm_backup', 'core.users', 'cro_number', 'UPDATE') THEN RAISE EXCEPTION 'T139: crm_backup nao atualiza o CRO'; END IF;
END $$;

-- matriz de papéis na prática: um orçamento APPROVED com parcela e pagamento, um documento, e a recepção olhando tudo
:ctx_a_d \gset
SELECT pg_temp.f3_fixture(:'ca', :'ua_d', :'pa', :'pt1', :'bP', :'iP', :'pP');
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('d1', pg_temp.cdoc_sql(:'d1', :'ca', :'pa', :'ua_d', 'CERTIFICATE'), 1);
SELECT pg_temp.expect_rows('ap', pg_temp.appt_sql('f6000000-0000-4000-8000-0000000000a2', :'ca', :'pa', :'ua_d', '2031-07-01T12:00:00Z', '2031-07-01T12:30:00Z', 'SCHEDULED'), 1);
SELECT pg_temp.expect_rows('T139 dentista le o documento', 'SELECT 1 FROM core.clinical_documents', 1);
SELECT pg_temp.expect_rows('T139 dentista le a parcela', 'SELECT 1 FROM core.installments', 1);
SELECT pg_temp.expect_rows('T139 dentista le o pagamento', 'SELECT 1 FROM core.payments', 1);
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T139 recepcao: 0 documentos', 'SELECT 1 FROM core.clinical_documents', 0);
SELECT pg_temp.expect_rows('T139 recepcao: 0 parcelas', 'SELECT 1 FROM core.installments', 0);
SELECT pg_temp.expect_rows('T139 recepcao: 0 pagamentos', 'SELECT 1 FROM core.payments', 0);
SELECT pg_temp.expect_rows('T139 recepcao le pacientes (dashboard K1)', 'SELECT 1 FROM core.patients WHERE active = true', 2);
SELECT pg_temp.expect_rows('T139 recepcao le orcamentos (C2)', 'SELECT 1 FROM core.budgets', 1);
SELECT pg_temp.expect_rows('T139 recepcao le agendamentos (dashboard K2/K5)', 'SELECT 1 FROM core.appointments', 1);

-- crm_app não faz DROP, ALTER nem TRUNCATE
SELECT pg_temp.expect_err('T139 crm_app nao faz DROP da tabela', '42501', 'DROP TABLE core.clinical_documents');
SELECT pg_temp.expect_err('T139 crm_app nao faz ALTER da tabela', '42501', 'ALTER TABLE core.clinical_documents DISABLE ROW LEVEL SECURITY');
SELECT pg_temp.expect_err('T139 crm_app nao desliga trigger', '42501', 'ALTER TABLE core.clinical_documents DISABLE TRIGGER trg_cdoc_no_delete');
SELECT pg_temp.expect_err('T139 crm_app nao faz TRUNCATE', '42501', 'TRUNCATE core.clinical_documents');
SELECT pg_temp.expect_err('T139 crm_app nao cria objeto em core', '42501', 'CREATE TABLE core.t139 (id int)');
SELECT pg_temp.expect_err('T139 crm_app nao executa a funcao de trigger de auditoria', '42501', 'SELECT core.write_document_audit()');
-- BYPASSRLS / role switching
SELECT pg_temp.expect_err('T139 crm_app nao vira crm_owner', '42501', 'SET ROLE crm_owner');
SELECT pg_temp.expect_err('T139 crm_app nao vira crm_definer', '42501', 'SET ROLE crm_definer');
RESET SESSION AUTHORIZATION;

ROLLBACK;
\o
SELECT 'phase4_permissions: OK' AS resultado;
