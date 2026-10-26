-- =====================================================================
-- tests/phase3_permissions.sql | Fase 3 / F08-phase3-verification
-- Testes 114 e 115 da spec global da Fase 3: privilégios e catálogo das 6 tabelas novas.
--   114  crm_app sem DELETE (exceto budget_items), sem UPDATE/DELETE/TRUNCATE em payments, sem TRUNCATE/DROP/ALTER; UPDATE de
--        budget_documents só na coluna deleted_at; crm_definer com o mínimo escopado ao tenant; crm_backup só leitura; EXECUTE do crm_app
--        só nas duas funções auxiliares.
--   115  ENABLE + FORCE e política por operação em cada tabela; política SELECT do crm_backup; funções novas com search_path fixo, sem
--        EXECUTE para PUBLIC e NENHUMA SECURITY DEFINER nova; nenhuma política USING (true) do definer; constraint trigger adiável.
-- Executar : como administrador, depois das migrations. Somente leitura do catálogo (e papéis em transação revertida).
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

CREATE FUNCTION pg_temp.expect_true(p_label text, p_sql text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v boolean;
BEGIN EXECUTE p_sql INTO v; IF v IS NOT TRUE THEN RAISE EXCEPTION '%: condicao falsa (%)', p_label, p_sql; END IF; END $$;

-- ---------------------------------------------------------------------
-- T114: privilégios de tabela
-- ---------------------------------------------------------------------
DO $$
DECLARE
  t text;
  tabs constant text[] := ARRAY['procedure_types', 'budgets', 'budget_items', 'budget_documents', 'installments', 'payments'];
BEGIN
  FOREACH t IN ARRAY tabs LOOP
    -- crm_app: lê e insere em todas; nunca TRUNCATE/REFERENCES/TRIGGER
    IF NOT (has_table_privilege('crm_app', 'core.' || t, 'SELECT') AND has_table_privilege('crm_app', 'core.' || t, 'INSERT')) THEN
      RAISE EXCEPTION 'T114: crm_app deve ter SELECT e INSERT em %', t;
    END IF;
    IF has_table_privilege('crm_app', 'core.' || t, 'TRUNCATE') OR has_table_privilege('crm_app', 'core.' || t, 'REFERENCES')
       OR has_table_privilege('crm_app', 'core.' || t, 'TRIGGER') THEN
      RAISE EXCEPTION 'T114: crm_app nao deve ter TRUNCATE/REFERENCES/TRIGGER em %', t;
    END IF;
    -- DELETE só em budget_items
    IF has_table_privilege('crm_app', 'core.' || t, 'DELETE') <> (t = 'budget_items') THEN
      RAISE EXCEPTION 'T114: DELETE do crm_app em % (esperado so em budget_items)', t;
    END IF;
    -- crm_backup: só SELECT
    IF NOT has_table_privilege('crm_backup', 'core.' || t, 'SELECT')
       OR has_table_privilege('crm_backup', 'core.' || t, 'INSERT') OR has_table_privilege('crm_backup', 'core.' || t, 'UPDATE')
       OR has_table_privilege('crm_backup', 'core.' || t, 'DELETE') THEN
      RAISE EXCEPTION 'T114: crm_backup so le %', t;
    END IF;
    -- crm_app nunca é dono
    IF (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = ('core.' || t)::regclass) <> 'crm_owner' THEN
      RAISE EXCEPTION 'T114: % deve pertencer ao crm_owner', t;
    END IF;
  END LOOP;
  -- UPDATE: nunca em payments; em budget_documents só na coluna deleted_at; nas demais, tabela inteira
  IF has_table_privilege('crm_app', 'core.payments', 'UPDATE') OR has_column_privilege('crm_app', 'core.payments', 'amount', 'UPDATE') THEN
    RAISE EXCEPTION 'T114: crm_app nao pode alterar payments';
  END IF;
  IF has_table_privilege('crm_app', 'core.budget_documents', 'UPDATE')
     OR NOT has_column_privilege('crm_app', 'core.budget_documents', 'deleted_at', 'UPDATE')
     OR has_column_privilege('crm_app', 'core.budget_documents', 'sha256', 'UPDATE')
     OR has_column_privilege('crm_app', 'core.budget_documents', 'storage_key', 'UPDATE') THEN
    RAISE EXCEPTION 'T114: budget_documents so aceita UPDATE em deleted_at';
  END IF;
  FOREACH t IN ARRAY ARRAY['procedure_types', 'budgets', 'budget_items', 'installments'] LOOP
    IF NOT has_table_privilege('crm_app', 'core.' || t, 'UPDATE') THEN RAISE EXCEPTION 'T114: crm_app deve ter UPDATE em %', t; END IF;
  END LOOP;

  -- crm_definer: o mínimo para a anonimização
  IF NOT has_table_privilege('crm_definer', 'core.budgets', 'SELECT')
     OR has_table_privilege('crm_definer', 'core.budgets', 'INSERT') OR has_table_privilege('crm_definer', 'core.budgets', 'DELETE')
     OR has_table_privilege('crm_definer', 'core.budgets', 'UPDATE')
     OR NOT has_column_privilege('crm_definer', 'core.budgets', 'notes', 'UPDATE')
     OR NOT has_column_privilege('crm_definer', 'core.budgets', 'version', 'UPDATE')
     OR has_column_privilege('crm_definer', 'core.budgets', 'total_amount', 'UPDATE')
     OR has_column_privilege('crm_definer', 'core.budgets', 'status', 'UPDATE')
     OR has_column_privilege('crm_definer', 'core.budgets', 'discount_amount', 'UPDATE') THEN
    RAISE EXCEPTION 'T114: crm_definer em budgets so tem SELECT e UPDATE (notes, version)';
  END IF;
  IF NOT has_table_privilege('crm_definer', 'core.installments', 'SELECT')
     OR has_table_privilege('crm_definer', 'core.installments', 'INSERT') OR has_table_privilege('crm_definer', 'core.installments', 'UPDATE')
     OR has_table_privilege('crm_definer', 'core.installments', 'DELETE') THEN
    RAISE EXCEPTION 'T114: crm_definer em installments so tem SELECT';
  END IF;
  IF NOT has_table_privilege('crm_definer', 'core.budget_documents', 'SELECT')
     OR has_table_privilege('crm_definer', 'core.budget_documents', 'INSERT') OR has_table_privilege('crm_definer', 'core.budget_documents', 'DELETE')
     OR has_table_privilege('crm_definer', 'core.budget_documents', 'UPDATE')
     OR NOT has_column_privilege('crm_definer', 'core.budget_documents', 'deleted_at', 'UPDATE')
     OR has_column_privilege('crm_definer', 'core.budget_documents', 'sha256', 'UPDATE') THEN
    RAISE EXCEPTION 'T114: crm_definer em budget_documents so tem SELECT e UPDATE (deleted_at)';
  END IF;
  FOREACH t IN ARRAY ARRAY['payments', 'budget_items', 'procedure_types'] LOOP
    IF has_table_privilege('crm_definer', 'core.' || t, 'SELECT') OR has_table_privilege('crm_definer', 'core.' || t, 'UPDATE')
       OR has_table_privilege('crm_definer', 'core.' || t, 'DELETE') OR has_table_privilege('crm_definer', 'core.' || t, 'INSERT') THEN
      RAISE EXCEPTION 'T114: crm_definer nao deve ter acesso a %', t;
    END IF;
  END LOOP;
END $$;

-- crm_app não faz DROP, ALTER nem TRUNCATE (não é dono)
SET SESSION AUTHORIZATION crm_app;
SELECT pg_temp.expect_err('T114 crm_app nao faz DROP TABLE', '42501', 'DROP TABLE core.payments');
SELECT pg_temp.expect_err('T114 crm_app nao faz ALTER TABLE', '42501', 'ALTER TABLE core.budgets ADD COLUMN hack int');
SELECT pg_temp.expect_err('T114 crm_app nao faz TRUNCATE de budgets', '42501', 'TRUNCATE core.budgets');
SELECT pg_temp.expect_err('T114 crm_app nao faz TRUNCATE de installments', '42501', 'TRUNCATE core.installments');
SELECT pg_temp.expect_err('T114 crm_app nao cria objetos em core', '42501', 'CREATE TABLE core.intruso (id int)');
RESET SESSION AUTHORIZATION;

-- EXECUTE: crm_app só nas duas funções auxiliares; nenhuma para PUBLIC
DO $$
DECLARE
  f record;
  novas constant text[] := ARRAY['budget_items_total', 'budgets_before_insert', 'guard_budget_update', 'guard_budget_item', 'refresh_budget_total',
    'budget_documents_before_insert', 'guard_budget_document_update', 'installment_net_paid', 'guard_installment', 'check_installments_sum',
    'guard_payment', 'sync_installment_status', 'payments_append_only'];
  aux constant text[] := ARRAY['budget_items_total', 'installment_net_paid'];
  n integer := 0;
BEGIN
  FOR f IN SELECT p.oid, p.proname, p.prosecdef, p.proconfig, p.proacl FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
            WHERE ns.nspname = 'core' AND p.proname = ANY (novas) LOOP
    n := n + 1;
    IF has_function_privilege('crm_app', f.oid, 'EXECUTE') <> (f.proname = ANY (aux)) THEN
      RAISE EXCEPTION 'T114: EXECUTE do crm_app em % (esperado so nas auxiliares)', f.proname;
    END IF;
    IF has_function_privilege('crm_backup', f.oid, 'EXECUTE') OR has_function_privilege('crm_definer', f.oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'T114: crm_backup/crm_definer nao devem executar %', f.proname;
    END IF;
    IF f.proacl IS NULL OR EXISTS (SELECT 1 FROM aclexplode(f.proacl) a WHERE a.grantee = 0) THEN
      RAISE EXCEPTION 'T115: % tem EXECUTE para PUBLIC (ou ACL padrao)', f.proname;
    END IF;
    IF f.prosecdef THEN RAISE EXCEPTION 'T115: % nao pode ser SECURITY DEFINER', f.proname; END IF;
    IF f.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(f.proconfig) c WHERE c LIKE 'search_path=%') THEN
      RAISE EXCEPTION 'T115: % sem search_path fixo', f.proname;
    END IF;
  END LOOP;
  IF n <> 13 THEN RAISE EXCEPTION 'T115: esperadas 13 funcoes novas, encontradas %', n; END IF;
  -- as únicas SECURITY DEFINER de core continuam sendo as das fases anteriores (write_audit e anonymize_patient, mais as de sessão/login)
  IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
              WHERE ns.nspname = 'core' AND p.prosecdef AND p.proname = ANY (novas)) THEN
    RAISE EXCEPTION 'T115: nenhuma funcao nova pode ser SECURITY DEFINER';
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- T115: RLS e políticas
-- ---------------------------------------------------------------------
DO $$
DECLARE
  t text; n integer;
  tabs constant text[] := ARRAY['procedure_types', 'budgets', 'budget_items', 'budget_documents', 'installments', 'payments'];
BEGIN
  FOREACH t IN ARRAY tabs LOOP
    IF NOT (SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = ('core.' || t)::regclass) THEN
      RAISE EXCEPTION 'T115: % sem ENABLE + FORCE ROW LEVEL SECURITY', t;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = ('core.' || t)::regclass AND p.polcmd = 'r'
                      AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_backup') = ANY (p.polroles)) THEN
      RAISE EXCEPTION 'T115: % sem politica de SELECT para crm_backup', t;
    END IF;
    -- operações com política para crm_app: SELECT sempre; INSERT sempre; UPDATE exceto payments; DELETE só em budget_items
    SELECT count(*) INTO n FROM pg_policy p WHERE p.polrelid = ('core.' || t)::regclass AND p.polcmd = 'a'
       AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles);
    IF n <> 1 THEN RAISE EXCEPTION 'T115: % deve ter 1 politica de INSERT para crm_app (tem %)', t, n; END IF;
    SELECT count(*) INTO n FROM pg_policy p WHERE p.polrelid = ('core.' || t)::regclass AND p.polcmd = 'w'
       AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles);
    IF n <> (CASE WHEN t = 'payments' THEN 0 ELSE 1 END) THEN RAISE EXCEPTION 'T115: politicas de UPDATE em % (obtido %)', t, n; END IF;
    SELECT count(*) INTO n FROM pg_policy p WHERE p.polrelid = ('core.' || t)::regclass AND p.polcmd = 'd'
       AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles);
    IF n <> (CASE WHEN t = 'budget_items' THEN 1 ELSE 0 END) THEN RAISE EXCEPTION 'T115: politicas de DELETE em % (obtido %)', t, n; END IF;
    -- nenhuma política com USING (true) para crm_app/PUBLIC; as do definer sempre escopadas ao tenant
    IF EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = ('core.' || t)::regclass AND pg_get_expr(p.polqual, p.polrelid) = 'true'
                  AND ((SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles) OR 0 = ANY (p.polroles)
                       OR (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') = ANY (p.polroles))) THEN
      RAISE EXCEPTION 'T115: % tem politica USING (true) para crm_app/PUBLIC/crm_definer', t;
    END IF;
  END LOOP;
  -- políticas do definer: escopadas ao tenant
  IF EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid IN ('core.budgets'::regclass, 'core.installments'::regclass, 'core.budget_documents'::regclass)
                AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') = ANY (p.polroles)
                AND pg_get_expr(p.polqual, p.polrelid) NOT LIKE '%current_clinic_id%') THEN
    RAISE EXCEPTION 'T115: politica do crm_definer sem escopo de tenant';
  END IF;
  IF (SELECT count(*) FROM pg_policy p WHERE p.polrelid IN ('core.budgets'::regclass, 'core.installments'::regclass, 'core.budget_documents'::regclass)
        AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') = ANY (p.polroles)) <> 5 THEN
    RAISE EXCEPTION 'T115: o crm_definer deve ter exatamente 5 politicas na Fase 3';
  END IF;
  -- nenhum papel de aplicação com BYPASSRLS ou SUPERUSER
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('crm_app', 'crm_definer', 'crm_backup') AND (rolbypassrls OR rolsuper)) THEN
    RAISE EXCEPTION 'T115: papel do CRM com BYPASSRLS ou superusuario';
  END IF;
  -- a constraint trigger da soma das parcelas é adiável e inicia adiada
  IF NOT EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgrelid = 'core.installments'::regclass AND t.tgname = 'ctrg_inst_sum_matches_total'
                    AND t.tgdeferrable AND t.tginitdeferred) THEN
    RAISE EXCEPTION 'T115: ctrg_inst_sum_matches_total deve ser DEFERRABLE INITIALLY DEFERRED';
  END IF;
  -- payments: triggers de somente inserção (linha e comando)
  IF (SELECT count(*) FROM pg_trigger t WHERE t.tgrelid = 'core.payments'::regclass AND t.tgname IN ('trg_pay_append_only', 'trg_pay_no_truncate')) <> 2 THEN
    RAISE EXCEPTION 'T115: payments sem os triggers de somente insercao';
  END IF;
  -- auditoria só nas 4 tabelas previstas
  IF (SELECT count(*) FROM pg_trigger t WHERE NOT t.tgisinternal AND t.tgname LIKE '%audit%'
        AND t.tgrelid IN ('core.budgets'::regclass, 'core.installments'::regclass, 'core.payments'::regclass, 'core.budget_documents'::regclass)) <> 4 THEN
    RAISE EXCEPTION 'T115: as 4 tabelas auditadas precisam do trigger de auditoria';
  END IF;
  -- DDL do contrato: tipos monetarios numeric(12,2)
  IF EXISTS (SELECT 1 FROM information_schema.columns c
              WHERE c.table_schema = 'core' AND c.table_name IN ('procedure_types', 'budgets', 'budget_items', 'installments', 'payments')
                AND c.column_name IN ('base_price', 'discount_amount', 'total_amount', 'unit_price', 'line_total', 'amount')
                AND NOT (c.data_type = 'numeric' AND c.numeric_precision = 12 AND c.numeric_scale = 2)) THEN
    RAISE EXCEPTION 'T115: toda coluna monetaria deve ser numeric(12,2)';
  END IF;
END $$;

ROLLBACK;
\o
SELECT 'phase3_permissions: OK' AS resultado;
