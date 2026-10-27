-- =====================================================================
-- tests/phase2_permissions.sql | Fase 2 / F08-phase2-verification
-- Testes 79 e 80 da spec global da Fase 2: matriz exata de privilégios (tabela e coluna) das quatro tabelas novas,
-- políticas por operação e por papel, RLS ENABLE + FORCE, funções novas (search_path, EXECUTE), fechamento das concessões
-- temporárias das V020/V021 e crm_app sem DDL nem DELETE (exceto clinic_holidays).
-- Executar : como administrador, depois das migrations.
-- =====================================================================
\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------
-- T79: matriz de privilégios de tabela: exatamente a spec global, seção 3.1
-- ---------------------------------------------------------------------
DO $$
DECLARE
  r record;
  v text;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('appointments',    'crm_app:INSERT, crm_app:SELECT, crm_app:UPDATE, crm_backup:SELECT, crm_definer:SELECT'),
      ('business_hours',  'crm_app:INSERT, crm_app:SELECT, crm_app:UPDATE, crm_backup:SELECT'),
      ('clinic_holidays', 'crm_app:DELETE, crm_app:INSERT, crm_app:SELECT, crm_app:UPDATE, crm_backup:SELECT'),
      ('holidays',        'crm_app:SELECT, crm_backup:SELECT')
    ) AS t(tbl, expected)
  LOOP
    SELECT string_agg(g, ', ' ORDER BY g) INTO v FROM (
      SELECT (CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE (SELECT rolname FROM pg_roles WHERE oid = a.grantee) END)
             || ':' || a.privilege_type AS g
        FROM pg_class c, LATERAL aclexplode(c.relacl) a
       WHERE c.oid = ('core.' || r.tbl)::regclass AND a.grantee <> c.relowner
    ) q;
    IF v IS DISTINCT FROM r.expected THEN
      RAISE EXCEPTION 'T79: privilegios de core.% divergem da spec: % (esperado %)', r.tbl, v, r.expected;
    END IF;
    IF (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = ('core.' || r.tbl)::regclass) <> 'crm_owner' THEN
      RAISE EXCEPTION 'T79: core.% deve pertencer a crm_owner', r.tbl;
    END IF;
    -- nunca TRUNCATE/REFERENCES/TRIGGER; crm_backup sem escrita; crm_app sem DELETE fora de clinic_holidays
    IF has_table_privilege('crm_app', 'core.' || r.tbl, 'TRUNCATE') OR has_table_privilege('crm_app', 'core.' || r.tbl, 'REFERENCES')
       OR has_table_privilege('crm_app', 'core.' || r.tbl, 'TRIGGER')
       OR has_table_privilege('crm_backup', 'core.' || r.tbl, 'INSERT') OR has_table_privilege('crm_backup', 'core.' || r.tbl, 'UPDATE')
       OR has_table_privilege('crm_backup', 'core.' || r.tbl, 'DELETE') OR has_table_privilege('crm_backup', 'core.' || r.tbl, 'TRUNCATE') THEN
      RAISE EXCEPTION 'T79: privilegio indevido em core.%', r.tbl;
    END IF;
    IF r.tbl <> 'clinic_holidays' AND has_table_privilege('crm_app', 'core.' || r.tbl, 'DELETE') THEN
      RAISE EXCEPTION 'T79: crm_app nao pode ter DELETE em core.% (so em clinic_holidays)', r.tbl;
    END IF;
    IF r.tbl = 'holidays' AND (has_table_privilege('crm_app', 'core.holidays', 'INSERT') OR has_table_privilege('crm_app', 'core.holidays', 'UPDATE')) THEN
      RAISE EXCEPTION 'T79: crm_app so le holidays';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c, LATERAL aclexplode(c.relacl) a WHERE c.oid = ('core.' || r.tbl)::regclass AND a.grantee = 0) THEN
      RAISE EXCEPTION 'T79: core.% tem privilegio para PUBLIC', r.tbl;
    END IF;
  END LOOP;
END $$;

-- privilégios por coluna: só crm_definer, e só UPDATE de notes e version em appointments
DO $$
DECLARE
  v text;
BEGIN
  SELECT string_agg(c.relname || '.' || at.attname || ':' || (SELECT rolname FROM pg_roles WHERE oid = a.grantee) || ':' || a.privilege_type,
                    ', ' ORDER BY at.attname)
    INTO v
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'core'
    JOIN pg_attribute at ON at.attrelid = c.oid AND at.attnum > 0 AND at.attacl IS NOT NULL,
         LATERAL aclexplode(at.attacl) a
   WHERE c.relname IN ('appointments', 'business_hours', 'clinic_holidays', 'holidays');
  IF v IS DISTINCT FROM 'appointments.notes:crm_definer:UPDATE, appointments.version:crm_definer:UPDATE' THEN
    RAISE EXCEPTION 'T79: privilegios por coluna divergentes: %', v;
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- T80: RLS ENABLE + FORCE e uma política por operação, por papel
-- ---------------------------------------------------------------------
DO $$
DECLARE
  r record;
  v text;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('appointments',    'INSERT,SELECT,UPDATE'),
      ('business_hours',  'INSERT,SELECT,UPDATE'),
      ('clinic_holidays', 'INSERT,DELETE,SELECT,UPDATE'),
      ('holidays',        'SELECT')
    ) AS t(tbl, expected)
  LOOP
    IF NOT (SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = ('core.' || r.tbl)::regclass) THEN
      RAISE EXCEPTION 'T80: core.% sem RLS ENABLE + FORCE', r.tbl;
    END IF;
    SELECT string_agg(CASE p.polcmd WHEN 'r' THEN 'SELECT' WHEN 'a' THEN 'INSERT' WHEN 'w' THEN 'UPDATE' WHEN 'd' THEN 'DELETE' ELSE 'ALL' END,
                      ',' ORDER BY p.polcmd) INTO v
      FROM pg_policy p
     WHERE p.polrelid = ('core.' || r.tbl)::regclass AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles);
    IF v IS DISTINCT FROM r.expected THEN
      RAISE EXCEPTION 'T80: politicas de crm_app em core.% divergentes: % (esperado %)', r.tbl, v, r.expected;
    END IF;
    -- política de leitura do backup: SELECT, USING (true)
    IF NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = ('core.' || r.tbl)::regclass AND p.polcmd = 'r'
                      AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_backup') = ANY (p.polroles)
                      AND pg_get_expr(p.polqual, p.polrelid) = 'true') THEN
      RAISE EXCEPTION 'T80: core.% sem politica de SELECT para crm_backup', r.tbl;
    END IF;
    -- nenhuma política para PUBLIC ou papel fora do conjunto esperado
    IF EXISTS (SELECT 1 FROM pg_policy p, LATERAL unnest(p.polroles) x(oid)
                WHERE p.polrelid = ('core.' || r.tbl)::regclass
                  AND (x.oid = 0 OR x.oid NOT IN (SELECT oid FROM pg_roles WHERE rolname IN ('crm_app', 'crm_backup', 'crm_definer', 'crm_owner')))) THEN
      RAISE EXCEPTION 'T80: core.% tem politica para PUBLIC ou papel inesperado', r.tbl;
    END IF;
    -- tabelas com clinic_id: toda política de crm_app e de crm_definer amarra o tenant; holidays é a exceção (dado global)
    IF r.tbl <> 'holidays' AND EXISTS (SELECT 1 FROM pg_policy p
                WHERE p.polrelid = ('core.' || r.tbl)::regclass
                  AND ((SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles)
                       OR (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') = ANY (p.polroles))
                  AND NOT (coalesce(pg_get_expr(p.polqual, p.polrelid), '') LIKE '%current_clinic_id%'
                           OR coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') LIKE '%current_clinic_id%')) THEN
      RAISE EXCEPTION 'T80: politica de core.% sem amarra com o tenant', r.tbl;
    END IF;
    -- o pg_dump reinterpreta varchar IN (...): politicas escritas como OR de igualdades (guarda de catalogo da restauracao)
    IF EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = ('core.' || r.tbl)::regclass
                AND (coalesce(pg_get_expr(p.polqual, p.polrelid), '') || coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')) ~ '= ANY') THEN
      RAISE EXCEPTION 'T80: politica de core.% usa IN/ANY (use OR de igualdades)', r.tbl;
    END IF;
  END LOOP;
END $$;

-- holidays: USING (true) só para crm_app (leitura) e crm_backup; o dono tem as 4 políticas dedicadas (seed por migration)
DO $$
DECLARE
  v text;
BEGIN
  SELECT string_agg(p.polname || ':' || p.polcmd::text, ', ' ORDER BY p.polname) INTO v
    FROM pg_policy p WHERE p.polrelid = 'core.holidays'::regclass AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_owner') = ANY (p.polroles);
  IF v IS DISTINCT FROM 'hol_owner_del:d, hol_owner_ins:a, hol_owner_sel:r, hol_owner_upd:w' THEN
    RAISE EXCEPTION 'T80: politicas do crm_owner em holidays divergentes: %', v;
  END IF;
  -- nenhum outro papel tem política de escrita em holidays
  IF EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = 'core.holidays'::regclass AND p.polcmd <> 'r'
                AND NOT ((SELECT oid FROM pg_roles WHERE rolname = 'crm_owner') = ANY (p.polroles) AND array_length(p.polroles, 1) = 1)) THEN
    RAISE EXCEPTION 'T80: so o crm_owner tem politica de escrita em holidays';
  END IF;
END $$;

-- políticas do crm_definer em appointments: exatamente SELECT e UPDATE, escopadas ao tenant, nenhuma aberta
DO $$
DECLARE
  names text[];
BEGIN
  SELECT array_agg(p.polname || ':' || p.polcmd::text ORDER BY p.polname) INTO names
    FROM pg_policy p WHERE p.polrelid = 'core.appointments'::regclass AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') = ANY (p.polroles);
  IF names IS DISTINCT FROM ARRAY['appt_definer_sel:r', 'appt_definer_upd:w'] THEN
    RAISE EXCEPTION 'T79: politicas do crm_definer em appointments divergentes: %', names;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = 'core.appointments'::regclass
                AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') = ANY (p.polroles)
                AND (coalesce(pg_get_expr(p.polqual, p.polrelid), '') = 'true' OR coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') = 'true')) THEN
    RAISE EXCEPTION 'T79: politica aberta (USING true) do crm_definer em appointments';
  END IF;
END $$;

-- a recepção: o texto das políticas de appointments reflete a spec (papéis, status e autoria)
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN SELECT p.polname, p.polcmd,
                  coalesce(pg_get_expr(p.polqual, p.polrelid), '') AS q, coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') AS w
             FROM pg_policy p WHERE p.polrelid = 'core.appointments'::regclass AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_app') = ANY (p.polroles) LOOP
    IF r.polname = 'appt_ins' AND (r.w NOT LIKE '%current_user_id%' OR r.w NOT LIKE '%SCHEDULED%' OR r.w NOT LIKE '%RECEPTIONIST%' OR r.w NOT LIKE '%DENTIST_ADMIN%') THEN
      RAISE EXCEPTION 'T65: appt_ins deve amarrar autoria e limitar a recepcao a SCHEDULED: %', r.w;
    END IF;
    IF r.polname = 'appt_upd' AND (r.q NOT LIKE '%SCHEDULED%' OR r.q NOT LIKE '%CONFIRMED%' OR r.w NOT LIKE '%CANCELED%' OR r.w NOT LIKE '%NO_SHOW%'
                                   OR r.w LIKE '%IN_PROGRESS%' OR r.w LIKE '%COMPLETED%') THEN
      RAISE EXCEPTION 'T65: appt_upd deve limitar a recepcao: USING=% CHECK=%', r.q, r.w;
    END IF;
  END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- T80: funções novas e concessões temporárias fechadas
-- ---------------------------------------------------------------------
DO $$
DECLARE
  names text[];
  f record;
BEGIN
  -- o conjunto SECURITY DEFINER não muda na Fase 2
  SELECT array_agg(p.proname ORDER BY p.proname) INTO names
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'core' AND p.prosecdef;
  IF names IS DISTINCT FROM ARRAY['anonymize_patient', 'find_refresh_token', 'find_user_for_login', 'purge_expired_refresh_tokens', 'record_document_download', 'write_audit', 'write_document_audit'] THEN
    RAISE EXCEPTION 'T80: funcoes SECURITY DEFINER inesperadas: %', names;
  END IF;

  FOR f IN SELECT p.oid, p.proname, p.prosecdef, p.proconfig, p.proacl, p.proowner
             FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'core' AND p.proname IN ('guard_appointment_patient', 'validate_clinic_timezone', 'anonymize_patient') LOOP
    IF f.proconfig IS NULL OR NOT (f.proconfig && ARRAY['search_path=pg_catalog, pg_temp']) THEN
      RAISE EXCEPTION 'T80: % sem search_path fixo: %', f.proname, f.proconfig;
    END IF;
    IF EXISTS (SELECT 1 FROM aclexplode(coalesce(f.proacl, acldefault('f', f.proowner))) a WHERE a.grantee = 0) THEN
      RAISE EXCEPTION 'T80: % executavel por PUBLIC', f.proname;
    END IF;
    IF has_function_privilege('crm_backup', f.oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'T80: % executavel por crm_backup', f.proname;
    END IF;
    IF f.proname <> 'anonymize_patient' THEN
      IF f.prosecdef THEN RAISE EXCEPTION 'T80: % nao pode ser SECURITY DEFINER', f.proname; END IF;
      IF has_function_privilege('crm_app', f.oid, 'EXECUTE') OR has_function_privilege('crm_definer', f.oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'T80: % (funcao de trigger) nao deve ser executavel por crm_app nem crm_definer', f.proname;
      END IF;
    ELSE
      -- a V021 troca o corpo e preserva dono, SECURITY DEFINER e EXECUTE
      IF NOT f.prosecdef OR f.proowner <> (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') THEN
        RAISE EXCEPTION 'T80: anonymize_patient deve continuar SECURITY DEFINER de crm_definer';
      END IF;
      IF NOT has_function_privilege('crm_app', f.oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'T80: crm_app deve continuar executando anonymize_patient';
      END IF;
      IF pg_get_functiondef(f.oid) !~ 'CR004' OR pg_get_functiondef(f.oid) !~ 'core\.appointments' THEN
        RAISE EXCEPTION 'T80: anonymize_patient deve ser a versao da V021 (CR004 e appointments)';
      END IF;
    END IF;
  END LOOP;

  -- concessões temporárias das V020/V021 foram fechadas
  IF has_schema_privilege('crm_definer', 'core', 'CREATE') THEN
    RAISE EXCEPTION 'T80: crm_definer manteve CREATE em core (V021)';
  END IF;
  IF has_function_privilege('crm_owner', 'core.write_audit()', 'EXECUTE') THEN
    RAISE EXCEPTION 'T80: crm_owner manteve EXECUTE em write_audit (V020)';
  END IF;
  IF has_function_privilege('crm_app', 'core.write_audit()', 'EXECUTE') THEN
    RAISE EXCEPTION 'T80: crm_app nao deve executar write_audit';
  END IF;
  -- crm_owner continua sem INHERIT de crm_definer (a premissa das V020/V021)
  IF EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
              WHERE g.rolname = 'crm_definer' AND m.rolname = 'crm_owner' AND am.inherit_option) THEN
    RAISE EXCEPTION 'T80: crm_owner nao deveria herdar privilegios de crm_definer';
  END IF;
END $$;

-- triggers da tabela de agendamentos: os esperados, todos habilitados
DO $$
DECLARE
  v text;
BEGIN
  SELECT string_agg(t.tgname, ', ' ORDER BY t.tgname) INTO v FROM pg_trigger t
   WHERE t.tgrelid = 'core.appointments'::regclass AND NOT t.tgisinternal AND t.tgenabled = 'O';
  IF v IS DISTINCT FROM 'trg_appt_audit, trg_appt_forbid_immutable, trg_appt_guard_patient, trg_appt_set_updated_at' THEN
    RAISE EXCEPTION 'T80: triggers de appointments divergentes: %', v;
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- T79/T18: crm_app não executa DDL nem operações destrutivas nas tabelas novas
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_app;
DO $$
DECLARE
  t text;
  s text;
  pn text;
  col text;
BEGIN
  FOREACH t IN ARRAY ARRAY['appointments', 'business_hours', 'clinic_holidays', 'holidays'] LOOP
    SELECT polname INTO pn FROM pg_policy WHERE polrelid = ('core.' || t)::regclass ORDER BY polname LIMIT 1;
    SELECT a.attname::text INTO col FROM pg_attribute a WHERE a.attrelid = ('core.' || t)::regclass AND a.attnum = 1;
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
      'CREATE INDEX ix_extra ON core.%1$s (%3$I)',
      'CREATE TRIGGER trg_extra BEFORE UPDATE ON core.%1$s FOR EACH ROW EXECUTE FUNCTION core.set_updated_at()',
      'ALTER TABLE core.%1$s DISABLE TRIGGER USER'
    ] LOOP
      BEGIN
        EXECUTE format(s, t, pn, col);
        RAISE EXCEPTION 'T18: crm_app executou: %', format(s, t, pn, col);
      EXCEPTION WHEN insufficient_privilege THEN NULL; WHEN syntax_error THEN NULL;
      END;
    END LOOP;
    IF t <> 'clinic_holidays' THEN
      BEGIN
        EXECUTE format('DELETE FROM core.%I', t);
        RAISE EXCEPTION 'T79: crm_app apagou de %', t;
      EXCEPTION WHEN insufficient_privilege THEN NULL;
      END;
    END IF;
    EXECUTE format('GRANT ALL ON core.%I TO PUBLIC', t);   -- sem grant option: só WARNING, nada é concedido
  END LOOP;
  FOREACH s IN ARRAY ARRAY[
    'ALTER TABLE core.appointments DROP CONSTRAINT ex_appt_no_overlap',
    'DROP FUNCTION core.guard_appointment_patient()',
    'ALTER FUNCTION core.anonymize_patient(uuid) OWNER TO crm_app',
    'CREATE OR REPLACE FUNCTION core.anonymize_patient(p uuid) RETURNS timestamptz LANGUAGE sql AS ''SELECT now()'''
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
              WHERE c.oid IN ('core.appointments'::regclass, 'core.business_hours'::regclass, 'core.clinic_holidays'::regclass, 'core.holidays'::regclass)
                AND a.grantee = 0) THEN
    RAISE EXCEPTION 'T18: crm_app conseguiu conceder privilegios a PUBLIC';
  END IF;
END $$;

-- crm_backup: somente leitura nas quatro tabelas
SET SESSION AUTHORIZATION crm_backup;
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['appointments', 'business_hours', 'clinic_holidays', 'holidays'] LOOP
    BEGIN EXECUTE format('DELETE FROM core.%I', t); RAISE EXCEPTION 'crm_backup apagou %', t;
    EXCEPTION WHEN insufficient_privilege THEN NULL; END;
    BEGIN EXECUTE format('TRUNCATE core.%I', t); RAISE EXCEPTION 'crm_backup truncou %', t;
    EXCEPTION WHEN insufficient_privilege THEN NULL; END;
    BEGIN EXECUTE format('SELECT 1 FROM core.%I', t); EXCEPTION WHEN insufficient_privilege THEN RAISE EXCEPTION 'crm_backup deve ler %', t; END;
  END LOOP;
END $$;
RESET SESSION AUTHORIZATION;

SELECT 'phase2_permissions: OK' AS resultado;
