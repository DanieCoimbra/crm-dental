-- =====================================================================
-- tests/phase1_privileges.sql | Fase 1 / F02-F07
-- Testes 17, 18, 52, 53 e 54 (parte) da spec global da Fase 1 para as 5 tabelas e as funções novas: matriz de
-- privilégios exata (tabela e coluna), políticas por papel, RLS ENABLE + FORCE, crm_app sem DDL nem DELETE,
-- funções SECURITY DEFINER e de trigger (dono, search_path, EXECUTE) e políticas do crm_definer escopadas ao tenant.
-- Executar : como administrador, depois das migrations.
-- =====================================================================
\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------
-- Matriz de privilégios de tabela: exatamente o contrato
-- ---------------------------------------------------------------------
DO $$
DECLARE
  r record;
  v text;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('patients',          'crm_app:SELECT, crm_backup:SELECT, crm_definer:SELECT, crm_definer:UPDATE'),
      ('medical_histories', 'crm_app:INSERT, crm_app:SELECT, crm_app:UPDATE, crm_backup:SELECT, crm_definer:DELETE, crm_definer:SELECT'),
      ('clinical_notes',    'crm_app:INSERT, crm_app:SELECT, crm_app:UPDATE, crm_backup:SELECT, crm_definer:DELETE, crm_definer:SELECT'),
      ('patient_files',     'crm_app:INSERT, crm_app:SELECT, crm_app:UPDATE, crm_backup:SELECT, crm_definer:SELECT, crm_definer:UPDATE'),
      ('audit_log',         'crm_app:SELECT, crm_backup:SELECT, crm_definer:INSERT')
    ) AS t(tbl, expected)
  LOOP
    SELECT string_agg(g, ', ' ORDER BY g) INTO v FROM (
      SELECT (CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE (SELECT rolname FROM pg_roles WHERE oid = a.grantee) END)
             || ':' || a.privilege_type AS g
        FROM pg_class c, LATERAL aclexplode(c.relacl) a
       WHERE c.oid = ('core.' || r.tbl)::regclass AND a.grantee <> c.relowner
    ) q;
    IF v IS DISTINCT FROM r.expected THEN
      RAISE EXCEPTION 'T52: privilegios de core.% divergem do contrato: % (esperado %)', r.tbl, v, r.expected;
    END IF;
    IF (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = ('core.' || r.tbl)::regclass) <> 'crm_owner' THEN
      RAISE EXCEPTION 'T52: core.% deve pertencer a crm_owner', r.tbl;
    END IF;
    -- nunca TRUNCATE/DELETE/REFERENCES/TRIGGER para crm_app; nada de escrita para crm_backup
    IF has_table_privilege('crm_app', 'core.' || r.tbl, 'DELETE') OR has_table_privilege('crm_app', 'core.' || r.tbl, 'TRUNCATE')
       OR has_table_privilege('crm_app', 'core.' || r.tbl, 'REFERENCES') OR has_table_privilege('crm_app', 'core.' || r.tbl, 'TRIGGER')
       OR has_table_privilege('crm_backup', 'core.' || r.tbl, 'INSERT') OR has_table_privilege('crm_backup', 'core.' || r.tbl, 'UPDATE') THEN
      RAISE EXCEPTION 'T52: privilegio indevido em core.%', r.tbl;
    END IF;
    -- sem GRANT em colunas para crm_backup nem crm_definer (só crm_app em patients)
    IF EXISTS (SELECT 1 FROM pg_attribute at, LATERAL aclexplode(at.attacl) a
                WHERE at.attrelid = ('core.' || r.tbl)::regclass AND at.attacl IS NOT NULL
                  AND a.grantee IN (SELECT oid FROM pg_roles WHERE rolname IN ('crm_backup', 'crm_definer'))) THEN
      RAISE EXCEPTION 'T52: privilegio por coluna inesperado em core.%', r.tbl;
    END IF;
    IF r.tbl <> 'patients' AND EXISTS (SELECT 1 FROM pg_attribute at
                WHERE at.attrelid = ('core.' || r.tbl)::regclass AND at.attacl IS NOT NULL AND at.attnum > 0) THEN
      RAISE EXCEPTION 'T52: core.% nao deve ter privilegios por coluna', r.tbl;
    END IF;
  END LOOP;
END $$;

-- Sequência da identidade de audit_log: o backup (pg_dump) a lê; a aplicação não precisa de privilégio nela
DO $$
BEGIN
  IF NOT has_sequence_privilege('crm_backup', 'core.audit_log_id_seq', 'SELECT')
     OR has_sequence_privilege('crm_backup', 'core.audit_log_id_seq', 'USAGE')
     OR has_sequence_privilege('crm_backup', 'core.audit_log_id_seq', 'UPDATE') THEN
    RAISE EXCEPTION 'T52: crm_backup deve ter apenas SELECT em core.audit_log_id_seq';
  END IF;
  IF has_sequence_privilege('crm_app', 'core.audit_log_id_seq', 'USAGE')
     OR has_sequence_privilege('crm_app', 'core.audit_log_id_seq', 'SELECT')
     OR has_sequence_privilege('crm_app', 'core.audit_log_id_seq', 'UPDATE')
     OR has_sequence_privilege('crm_definer', 'core.audit_log_id_seq', 'USAGE')
     OR has_sequence_privilege('crm_definer', 'core.audit_log_id_seq', 'UPDATE') THEN
    RAISE EXCEPTION 'T52: crm_app e crm_definer nao precisam de privilegio na sequencia de audit_log';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_class c, LATERAL aclexplode(coalesce(c.relacl, acldefault('S', c.relowner))) a
              WHERE c.oid = 'core.audit_log_id_seq'::regclass AND a.grantee = 0) THEN
    RAISE EXCEPTION 'T52: a sequencia de audit_log nao pode ter privilegio para PUBLIC';
  END IF;
END $$;

-- Privilégios por coluna de patients para crm_app: anonymized_at e imutáveis ficam de fora
DO $$
DECLARE
  c record;
BEGIN
  FOR c IN SELECT a.attname::text AS col FROM pg_attribute a
            WHERE a.attrelid = 'core.patients'::regclass AND a.attnum > 0 AND NOT a.attisdropped LOOP
    IF c.col = 'anonymized_at' THEN
      IF has_column_privilege('crm_app', 'core.patients', c.col, 'INSERT') OR has_column_privilege('crm_app', 'core.patients', c.col, 'UPDATE') THEN
        RAISE EXCEPTION 'T51: crm_app nao pode gravar anonymized_at';
      END IF;
    ELSIF c.col IN ('id', 'clinic_id', 'created_by', 'created_at') THEN
      IF NOT has_column_privilege('crm_app', 'core.patients', c.col, 'INSERT') OR has_column_privilege('crm_app', 'core.patients', c.col, 'UPDATE') THEN
        RAISE EXCEPTION 'T51: coluna imutavel % deve aceitar INSERT e nao UPDATE', c.col;
      END IF;
    ELSE
      IF NOT (has_column_privilege('crm_app', 'core.patients', c.col, 'INSERT') AND has_column_privilege('crm_app', 'core.patients', c.col, 'UPDATE')) THEN
        RAISE EXCEPTION 'T51: crm_app deve gravar a coluna %', c.col;
      END IF;
    END IF;
  END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- RLS e políticas por papel (teste 54 e matriz da spec, seção 3.2)
-- ---------------------------------------------------------------------
DO $$
DECLARE
  r record;
  v text;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('patients',          'INSERT,SELECT,UPDATE'),
      ('medical_histories', 'INSERT,SELECT,UPDATE'),
      ('clinical_notes',    'INSERT,SELECT,UPDATE'),
      ('patient_files',     'INSERT,SELECT,UPDATE'),
      ('audit_log',         'SELECT')
    ) AS t(tbl, expected)
  LOOP
    IF NOT (SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = ('core.' || r.tbl)::regclass) THEN
      RAISE EXCEPTION 'T54: core.% sem RLS ENABLE + FORCE', r.tbl;
    END IF;
    SELECT string_agg(CASE p.polcmd WHEN 'r' THEN 'SELECT' WHEN 'a' THEN 'INSERT' WHEN 'w' THEN 'UPDATE' WHEN 'd' THEN 'DELETE' ELSE 'ALL' END,
                      ',' ORDER BY p.polcmd) INTO v
      FROM pg_policy p
     WHERE p.polrelid = ('core.' || r.tbl)::regclass
       AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles);
    IF v IS DISTINCT FROM r.expected THEN
      RAISE EXCEPTION 'T54: politicas de crm_app em core.% divergentes: % (esperado %)', r.tbl, v, r.expected;
    END IF;
    -- toda política de crm_app amarra a clínica da sessão; nenhuma é aberta
    IF EXISTS (SELECT 1 FROM pg_policy p
                WHERE p.polrelid = ('core.' || r.tbl)::regclass
                  AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles)
                  AND NOT (coalesce(pg_get_expr(p.polqual, p.polrelid), '') LIKE '%current_clinic_id%'
                           OR coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') LIKE '%current_clinic_id%')) THEN
      RAISE EXCEPTION 'T54: politica de crm_app em core.% sem amarra com o tenant', r.tbl;
    END IF;
    -- só crm_app, crm_backup e crm_definer têm política; nenhuma para PUBLIC
    IF EXISTS (SELECT 1 FROM pg_policy p, LATERAL unnest(p.polroles) x(oid)
                WHERE p.polrelid = ('core.' || r.tbl)::regclass
                  AND (x.oid = 0 OR x.oid NOT IN (SELECT oid FROM pg_roles WHERE rolname IN ('crm_app', 'crm_backup', 'crm_definer')))) THEN
      RAISE EXCEPTION 'T54: core.% tem politica para PUBLIC ou papel inesperado', r.tbl;
    END IF;
  END LOOP;
END $$;

-- Políticas que dependem de app.role: prontuário e auditoria só DENTIST_ADMIN; exames: INSERT para os dois
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN SELECT p.polrelid::regclass::text AS tbl, p.polname, p.polcmd,
                  coalesce(pg_get_expr(p.polqual, p.polrelid), '') || ' ' || coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') AS expr
             FROM pg_policy p
            WHERE p.polrelid IN ('core.medical_histories'::regclass, 'core.clinical_notes'::regclass, 'core.patient_files'::regclass,
                                 'core.audit_log'::regclass, 'core.patients'::regclass)
              AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles) LOOP
    IF r.tbl = 'core.patients' THEN
      IF r.expr NOT LIKE '%RECEPTIONIST%' OR r.expr NOT LIKE '%DENTIST_ADMIN%' THEN
        RAISE EXCEPTION 'T35: politica % deve aceitar os dois papeis', r.polname;
      END IF;
    ELSIF r.tbl = 'core.patient_files' AND r.polcmd = 'a' THEN
      IF r.expr NOT LIKE '%RECEPTIONIST%' OR r.expr NOT LIKE '%DENTIST_ADMIN%' OR r.expr NOT LIKE '%current_user_id%' THEN
        RAISE EXCEPTION 'T33: INSERT de exame deve aceitar os dois papeis e amarrar a autoria';
      END IF;
    ELSE
      IF r.expr LIKE '%RECEPTIONIST%' OR r.expr NOT LIKE '%DENTIST_ADMIN%' THEN
        RAISE EXCEPTION 'T32: politica % deve exigir apenas DENTIST_ADMIN', r.polname;
      END IF;
      IF r.polcmd = 'a' AND r.expr NOT LIKE '%current_user_id%' THEN
        RAISE EXCEPTION 'T36: INSERT em % deve amarrar created_by a app.user_id', r.tbl;
      END IF;
    END IF;
  END LOOP;
END $$;

-- Políticas do crm_definer: exatamente as da spec, todas escopadas ao tenant, nenhuma aberta
DO $$
DECLARE
  names text[];
BEGIN
  SELECT array_agg(c.relname || ':' || p.polname || ':' || p.polcmd::text ORDER BY c.relname, p.polname) INTO names
    FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
   WHERE (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') = ANY (p.polroles)
     AND c.relname IN ('patients', 'patient_files', 'medical_histories', 'clinical_notes', 'audit_log');
  IF names IS DISTINCT FROM ARRAY['audit_log:audit_definer_ins:a', 'clinical_notes:notes_definer_del:d', 'clinical_notes:notes_definer_sel:r',
        'medical_histories:histories_definer_del:d', 'medical_histories:histories_definer_sel:r',
        'patient_files:files_definer_sel:r', 'patient_files:files_definer_upd:w',
        'patients:patients_definer_sel:r', 'patients:patients_definer_upd:w'] THEN
    RAISE EXCEPTION 'T53: politicas do crm_definer divergentes: %', names;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
              WHERE (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') = ANY (p.polroles)
                AND c.relname IN ('patients', 'patient_files', 'medical_histories', 'clinical_notes', 'audit_log')
                AND NOT (coalesce(pg_get_expr(p.polqual, p.polrelid), '') LIKE '%current_clinic_id%'
                         OR coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') LIKE '%current_clinic_id%')) THEN
    RAISE EXCEPTION 'T53: politica do crm_definer sem amarra com o tenant (USING true e proibido nesta fase)';
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- Funções: SECURITY DEFINER, dono, search_path, EXECUTE (teste 53)
-- ---------------------------------------------------------------------
DO $$
DECLARE
  names text[];
  f record;
BEGIN
  -- conjunto de funções SECURITY DEFINER do schema: as 3 da Fase 0, write_audit e anonymize_patient (Fase 1) e write_document_audit e record_document_download (Fase 4)
  SELECT array_agg(p.proname ORDER BY p.proname) INTO names
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'core' AND p.prosecdef;
  IF names IS DISTINCT FROM ARRAY['anonymize_patient', 'find_refresh_token', 'find_user_for_login', 'purge_expired_refresh_tokens', 'record_document_download', 'write_audit', 'write_document_audit'] THEN
    RAISE EXCEPTION 'T53: funcoes SECURITY DEFINER inesperadas: %', names;
  END IF;

  FOR f IN SELECT p.oid, p.proname, p.prosecdef, p.proowner, p.proconfig, p.proacl, p.provolatile
             FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'core'
              AND p.proname IN ('write_audit', 'anonymize_patient', 'audit_log_append_only', 'guard_anonymized_patient',
                                'forbid_child_of_anonymized', 'current_app_role', 'current_user_id', 'clinical_retention_period') LOOP
    IF f.proconfig IS NULL OR NOT (f.proconfig && ARRAY['search_path=pg_catalog, pg_temp', 'search_path=pg_catalog']) THEN
      RAISE EXCEPTION 'T53: % sem search_path fixo: %', f.proname, f.proconfig;
    END IF;
    IF EXISTS (SELECT 1 FROM aclexplode(coalesce(f.proacl, acldefault('f', f.proowner))) a WHERE a.grantee = 0) THEN
      RAISE EXCEPTION 'T53: % executavel por PUBLIC', f.proname;
    END IF;
    IF has_function_privilege('crm_backup', f.oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'T53: % executavel por crm_backup', f.proname;
    END IF;
    IF f.proname IN ('write_audit', 'anonymize_patient') THEN
      IF NOT f.prosecdef OR f.proowner <> (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') THEN
        RAISE EXCEPTION 'T53: % deve ser SECURITY DEFINER de crm_definer', f.proname;
      END IF;
      IF NOT f.proconfig @> ARRAY['search_path=pg_catalog, pg_temp'] THEN
        RAISE EXCEPTION 'T53: % deve fixar search_path = pg_catalog, pg_temp', f.proname;
      END IF;
      IF f.provolatile <> 'v' THEN RAISE EXCEPTION 'T53: % deve ser VOLATILE', f.proname; END IF;
    ELSIF f.prosecdef THEN
      RAISE EXCEPTION 'T53: % nao pode ser SECURITY DEFINER', f.proname;
    END IF;
  END LOOP;

  -- EXECUTE: anonymize_patient só crm_app; write_audit e as de trigger ninguém além do dono
  IF NOT has_function_privilege('crm_app', 'core.anonymize_patient(uuid)', 'EXECUTE') THEN
    RAISE EXCEPTION 'T53: crm_app deve executar core.anonymize_patient';
  END IF;
  IF has_function_privilege('crm_app', 'core.write_audit()', 'EXECUTE')
     OR has_function_privilege('crm_app', 'core.audit_log_append_only()', 'EXECUTE')
     OR has_function_privilege('crm_app', 'core.guard_anonymized_patient()', 'EXECUTE')
     OR has_function_privilege('crm_app', 'core.forbid_child_of_anonymized()', 'EXECUTE') THEN
    RAISE EXCEPTION 'T53: crm_app nao deve executar funcoes de trigger';
  END IF;
  -- crm_definer sem CREATE em core ao fim das migrations
  IF has_schema_privilege('crm_definer', 'core', 'CREATE') THEN
    RAISE EXCEPTION 'T53: crm_definer manteve CREATE em core';
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- T18 / T52: crm_app não executa DDL nem operações destrutivas nas tabelas novas
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_app;
DO $$
DECLARE
  t text;
  s text;
  pn text;
BEGIN
  FOREACH t IN ARRAY ARRAY['patients', 'medical_histories', 'clinical_notes', 'patient_files', 'audit_log'] LOOP
    SELECT polname INTO pn FROM pg_policy WHERE polrelid = ('core.' || t)::regclass ORDER BY polname LIMIT 1;
    IF pn IS NULL THEN RAISE EXCEPTION 'T18: core.% sem politicas para testar', t; END IF;
    FOREACH s IN ARRAY ARRAY[
      'DROP TABLE core.%1$s',
      'TRUNCATE core.%1$s',
      'ALTER TABLE core.%1$s ADD COLUMN extra integer',
      'ALTER TABLE core.%1$s DISABLE ROW LEVEL SECURITY',
      'ALTER TABLE core.%1$s NO FORCE ROW LEVEL SECURITY',
      'DROP POLICY %2$I ON core.%1$s',
      'ALTER POLICY %2$I ON core.%1$s USING (true)',
      'CREATE POLICY p_extra ON core.%1$s FOR SELECT TO crm_app USING (true)',
      'CREATE INDEX ix_extra ON core.%1$s (id)',
      'CREATE TRIGGER trg_extra BEFORE UPDATE ON core.%1$s FOR EACH ROW EXECUTE FUNCTION core.set_updated_at()',
      'ALTER TABLE core.%1$s DISABLE TRIGGER USER',
      'DELETE FROM core.%1$s'
    ] LOOP
      BEGIN
        EXECUTE format(s, t, pn);
        RAISE EXCEPTION 'T18: crm_app executou: %', format(s, t, pn);
      EXCEPTION WHEN insufficient_privilege THEN NULL;
      END;
    END LOOP;
    EXECUTE format('GRANT ALL ON core.%I TO PUBLIC', t);   -- sem grant option: só emite WARNING, nada é concedido
  END LOOP;
  FOREACH s IN ARRAY ARRAY[
    'ALTER FUNCTION core.anonymize_patient(uuid) OWNER TO crm_app',
    'CREATE OR REPLACE FUNCTION core.anonymize_patient(p uuid) RETURNS timestamptz LANGUAGE sql AS ''SELECT now()''',
    'DROP FUNCTION core.write_audit()',
    'SET ROLE crm_definer'
  ] LOOP
    BEGIN
      EXECUTE s;
      RAISE EXCEPTION 'T18: crm_app executou: %', s;
    EXCEPTION WHEN insufficient_privilege THEN NULL; WHEN undefined_function THEN NULL;
    END;
  END LOOP;
END $$;
RESET SESSION AUTHORIZATION;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_class c, LATERAL aclexplode(c.relacl) a
              WHERE c.oid IN ('core.patients'::regclass, 'core.medical_histories'::regclass, 'core.clinical_notes'::regclass,
                              'core.patient_files'::regclass, 'core.audit_log'::regclass) AND a.grantee = 0) THEN
    RAISE EXCEPTION 'T18: crm_app conseguiu conceder privilegios a PUBLIC';
  END IF;
END $$;

-- crm_backup: somente leitura nas tabelas novas
SET SESSION AUTHORIZATION crm_backup;
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['patients', 'medical_histories', 'clinical_notes', 'patient_files', 'audit_log'] LOOP
    BEGIN EXECUTE format('DELETE FROM core.%I', t); RAISE EXCEPTION 'crm_backup apagou %', t;
    EXCEPTION WHEN insufficient_privilege THEN NULL; END;
    BEGIN EXECUTE format('TRUNCATE core.%I', t); RAISE EXCEPTION 'crm_backup truncou %', t;
    EXCEPTION WHEN insufficient_privilege THEN NULL; END;
    BEGIN EXECUTE format('SELECT 1 FROM core.%I', t); EXCEPTION WHEN insufficient_privilege THEN RAISE EXCEPTION 'crm_backup deve ler %', t; END;
  END LOOP;
  BEGIN PERFORM core.anonymize_patient(gen_random_uuid()); RAISE EXCEPTION 'crm_backup executou anonymize_patient';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET SESSION AUTHORIZATION;

SELECT 'phase1_privileges: OK' AS resultado;
