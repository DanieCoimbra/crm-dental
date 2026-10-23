-- =====================================================================
-- tests/schema_dictionary.sql | F03-core-tables
-- Confere o schema real contra o dicionário do contract (colunas, tipos, tamanhos, nulidade,
-- defaults) e os índices. É a prova de que o mapeamento JPA (ddl-auto=validate) tem os tipos
-- esperados: varchar (nunca char/text), uuid, timestamptz, bigint.
-- Executar : como administrador, depois das migrations.
-- =====================================================================
\set ON_ERROR_STOP on

CREATE TEMP TABLE expected_cols (
  tbl text, col text, dtype text, maxlen int, nullable text, dflt text
);

INSERT INTO expected_cols VALUES
 ('clinics','id','uuid',NULL,'NO','gen_random_uuid()'),
 ('clinics','name','character varying',150,'NO',NULL),
 ('clinics','document','character varying',20,'YES',NULL),
 ('clinics','phone','character varying',20,'YES',NULL),
 ('clinics','email','character varying',254,'YES',NULL),
 ('clinics','address','character varying',255,'YES',NULL),
 ('clinics','created_at','timestamp with time zone',NULL,'NO','now()'),
 ('clinics','updated_at','timestamp with time zone',NULL,'NO','now()'),
 ('clinics','version','bigint',NULL,'NO','0'),
 ('users','id','uuid',NULL,'NO','gen_random_uuid()'),
 ('users','clinic_id','uuid',NULL,'NO',NULL),
 ('users','name','character varying',120,'NO',NULL),
 ('users','email','character varying',254,'NO',NULL),
 ('users','password_hash','character varying',100,'NO',NULL),
 ('users','role','character varying',20,'NO',NULL),
 ('users','active','boolean',NULL,'NO','true'),
 ('users','created_by','uuid',NULL,'YES',NULL),
 ('users','created_at','timestamp with time zone',NULL,'NO','now()'),
 ('users','updated_at','timestamp with time zone',NULL,'NO','now()'),
 ('users','version','bigint',NULL,'NO','0'),
 ('refresh_tokens','id','uuid',NULL,'NO','gen_random_uuid()'),
 ('refresh_tokens','clinic_id','uuid',NULL,'NO',NULL),
 ('refresh_tokens','user_id','uuid',NULL,'NO',NULL),
 ('refresh_tokens','family_id','uuid',NULL,'NO',NULL),
 ('refresh_tokens','token_hash','character varying',64,'NO',NULL),
 ('refresh_tokens','expires_at','timestamp with time zone',NULL,'NO',NULL),
 ('refresh_tokens','used_at','timestamp with time zone',NULL,'YES',NULL),
 ('refresh_tokens','revoked_at','timestamp with time zone',NULL,'YES',NULL),
 ('refresh_tokens','created_at','timestamp with time zone',NULL,'NO','now()'),
 ('patients','id','uuid',NULL,'NO','gen_random_uuid()'),
 ('patients','clinic_id','uuid',NULL,'NO',NULL),
 ('patients','name','character varying',120,'NO',NULL),
 ('patients','cpf','character varying',11,'YES',NULL),
 ('patients','birth_date','date',NULL,'NO',NULL),
 ('patients','sex','character varying',15,'NO','''NOT_INFORMED''::character varying'),
 ('patients','profession','character varying',100,'YES',NULL),
 ('patients','phone','character varying',20,'NO',NULL),
 ('patients','email','character varying',254,'YES',NULL),
 ('patients','address','character varying',255,'YES',NULL),
 ('patients','insurance_name','character varying',100,'YES',NULL),
 ('patients','insurance_number','character varying',40,'YES',NULL),
 ('patients','acquaintance_name','character varying',120,'YES',NULL),
 ('patients','acquaintance_phone','character varying',20,'YES',NULL),
 ('patients','active','boolean',NULL,'NO','true'),
 ('patients','deactivated_at','timestamp with time zone',NULL,'YES',NULL),
 ('patients','anonymized_at','timestamp with time zone',NULL,'YES',NULL),
 ('patients','created_by','uuid',NULL,'NO',NULL),
 ('patients','created_at','timestamp with time zone',NULL,'NO','now()'),
 ('patients','updated_at','timestamp with time zone',NULL,'NO','now()'),
 ('patients','version','bigint',NULL,'NO','0'),
 ('medical_histories','id','uuid',NULL,'NO','gen_random_uuid()'),
 ('medical_histories','clinic_id','uuid',NULL,'NO',NULL),
 ('medical_histories','patient_id','uuid',NULL,'NO',NULL),
 ('medical_histories','content','character varying',54000,'NO',NULL),
 ('medical_histories','created_by','uuid',NULL,'NO',NULL),
 ('medical_histories','created_at','timestamp with time zone',NULL,'NO','now()'),
 ('medical_histories','updated_at','timestamp with time zone',NULL,'NO','now()'),
 ('medical_histories','version','bigint',NULL,'NO','0'),
 ('clinical_notes','id','uuid',NULL,'NO','gen_random_uuid()'),
 ('clinical_notes','clinic_id','uuid',NULL,'NO',NULL),
 ('clinical_notes','patient_id','uuid',NULL,'NO',NULL),
 ('clinical_notes','noted_at','timestamp with time zone',NULL,'NO','now()'),
 ('clinical_notes','content','character varying',27000,'NO',NULL),
 ('clinical_notes','created_by','uuid',NULL,'NO',NULL),
 ('clinical_notes','created_at','timestamp with time zone',NULL,'NO','now()'),
 ('clinical_notes','updated_at','timestamp with time zone',NULL,'NO','now()'),
 ('clinical_notes','version','bigint',NULL,'NO','0'),
 ('patient_files','id','uuid',NULL,'NO','gen_random_uuid()'),
 ('patient_files','clinic_id','uuid',NULL,'NO',NULL),
 ('patient_files','patient_id','uuid',NULL,'NO',NULL),
 ('patient_files','kind','character varying',20,'NO',NULL),
 ('patient_files','storage_key','character varying',120,'NO',NULL),
 ('patient_files','original_filename','character varying',255,'NO',NULL),
 ('patient_files','content_type','character varying',50,'NO',NULL),
 ('patient_files','size_bytes','bigint',NULL,'NO',NULL),
 ('patient_files','deleted_at','timestamp with time zone',NULL,'YES',NULL),
 ('patient_files','created_by','uuid',NULL,'NO',NULL),
 ('patient_files','created_at','timestamp with time zone',NULL,'NO','now()'),
 ('patient_files','updated_at','timestamp with time zone',NULL,'NO','now()'),
 ('patient_files','version','bigint',NULL,'NO','0'),
 ('appointments','id','uuid',NULL,'NO','gen_random_uuid()'),
 ('appointments','clinic_id','uuid',NULL,'NO',NULL),
 ('appointments','patient_id','uuid',NULL,'NO',NULL),
 ('appointments','starts_at','timestamp with time zone',NULL,'NO',NULL),
 ('appointments','ends_at','timestamp with time zone',NULL,'NO',NULL),
 ('appointments','status','character varying',15,'NO','''SCHEDULED''::character varying'),
 ('appointments','notes','character varying',500,'YES',NULL),
 ('appointments','procedure_type_id','uuid',NULL,'YES',NULL),
 ('appointments','created_by','uuid',NULL,'NO',NULL),
 ('appointments','created_at','timestamp with time zone',NULL,'NO','now()'),
 ('appointments','updated_at','timestamp with time zone',NULL,'NO','now()'),
 ('appointments','version','bigint',NULL,'NO','0'),
 ('business_hours','id','uuid',NULL,'NO','gen_random_uuid()'),
 ('business_hours','clinic_id','uuid',NULL,'NO',NULL),
 ('business_hours','weekday','smallint',NULL,'NO',NULL),
 ('business_hours','is_open','boolean',NULL,'NO',NULL),
 ('business_hours','opens_at','time without time zone',NULL,'YES',NULL),
 ('business_hours','closes_at','time without time zone',NULL,'YES',NULL),
 ('business_hours','lunch_starts_at','time without time zone',NULL,'YES',NULL),
 ('business_hours','lunch_ends_at','time without time zone',NULL,'YES',NULL),
 ('business_hours','created_by','uuid',NULL,'NO',NULL),
 ('business_hours','created_at','timestamp with time zone',NULL,'NO','now()'),
 ('business_hours','updated_at','timestamp with time zone',NULL,'NO','now()'),
 ('business_hours','version','bigint',NULL,'NO','0'),
 ('clinic_holidays','id','uuid',NULL,'NO','gen_random_uuid()'),
 ('clinic_holidays','clinic_id','uuid',NULL,'NO',NULL),
 ('clinic_holidays','holiday_date','date',NULL,'NO',NULL),
 ('clinic_holidays','name','character varying',80,'NO',NULL),
 ('clinic_holidays','created_by','uuid',NULL,'NO',NULL),
 ('clinic_holidays','created_at','timestamp with time zone',NULL,'NO','now()'),
 ('clinic_holidays','updated_at','timestamp with time zone',NULL,'NO','now()'),
 ('clinic_holidays','version','bigint',NULL,'NO','0'),
 ('holidays','holiday_date','date',NULL,'NO',NULL),
 ('holidays','name','character varying',80,'NO',NULL),
 ('clinical_notes','appointment_id','uuid',NULL,'YES',NULL),
 ('clinics','timezone','character varying',50,'NO','''America/Sao_Paulo''::character varying'),
 ('audit_log','id','bigint',NULL,'NO',NULL),
 ('audit_log','clinic_id','uuid',NULL,'NO',NULL),
 ('audit_log','user_id','uuid',NULL,'NO',NULL),
 ('audit_log','table_name','character varying',30,'NO',NULL),
 ('audit_log','record_id','uuid',NULL,'NO',NULL),
 ('audit_log','action','character varying',10,'NO',NULL),
 ('audit_log','occurred_at','timestamp with time zone',NULL,'NO','now()');

DO $$
DECLARE
  v text;
BEGIN
  -- Diferenças entre esperado e real (colunas faltando, extras, tipos, tamanhos, nulidade, default)
  SELECT string_agg(d, E'\n') INTO v FROM (
    SELECT format('falta ou difere: %s.%s (esperado %s/%s/%s/%s)', e.tbl, e.col, e.dtype, e.maxlen, e.nullable, e.dflt) AS d
      FROM expected_cols e
      LEFT JOIN information_schema.columns c
        ON c.table_schema = 'core' AND c.table_name = e.tbl AND c.column_name = e.col
     WHERE c.column_name IS NULL
        OR c.data_type <> e.dtype
        OR c.character_maximum_length IS DISTINCT FROM e.maxlen
        OR c.is_nullable <> e.nullable
        OR c.column_default IS DISTINCT FROM e.dflt
    UNION ALL
    SELECT format('coluna extra fora do contrato: %s.%s', c.table_name, c.column_name)
      FROM information_schema.columns c
     WHERE c.table_schema = 'core' AND c.table_name IN ('clinics', 'users', 'refresh_tokens', 'patients', 'medical_histories', 'clinical_notes', 'patient_files', 'audit_log',
                                'appointments', 'business_hours', 'clinic_holidays', 'holidays')
       AND NOT EXISTS (SELECT 1 FROM expected_cols e WHERE e.tbl = c.table_name AND e.col = c.column_name)
  ) x;
  IF v IS NOT NULL THEN
    RAISE EXCEPTION E'dicionario divergente:\n%', v;
  END IF;

  -- FR-9: nenhuma coluna de plano/trial/cobrança/expiração de conta
  IF EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = 'core' AND table_name IN ('clinics', 'users', 'refresh_tokens', 'patients', 'medical_histories', 'clinical_notes', 'patient_files', 'audit_log',
                                'appointments', 'business_hours', 'clinic_holidays', 'holidays')
                AND column_name ~* '(plan|trial|billing|subscription|account_expir|paid)') THEN
    RAISE EXCEPTION 'FR-9: coluna de plano/trial/cobranca encontrada';
  END IF;

  -- Nenhum char(n)/text nas tabelas (compatibilidade com o ddl-auto=validate)
  IF EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = 'core' AND table_name IN ('clinics', 'users', 'refresh_tokens', 'patients', 'medical_histories', 'clinical_notes', 'patient_files', 'audit_log',
                                'appointments', 'business_hours', 'clinic_holidays', 'holidays')
                AND data_type IN ('character', 'text')) THEN
    RAISE EXCEPTION 'tipos char/text nao sao permitidos nestas tabelas';
  END IF;
END $$;

-- Índices do contrato (nome, unicidade, predicado parcial, colunas)
DO $$
DECLARE
  r record;
  def text;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('ux_users_email',       true,  '(email)',             NULL),
      ('ux_users_one_dentist', true,  '(clinic_id)',         'DENTIST_ADMIN'),
      ('ux_users_id_clinic',   true,  '(id, clinic_id)',     NULL),
      ('ix_users_clinic_name', false, '(clinic_id, name)',   NULL),
      ('ux_refresh_hash',      true,  '(token_hash)',        NULL),
      ('ix_refresh_user',      false, '(user_id)',           NULL),
      ('ix_refresh_family',    false, '(family_id)',         NULL),
      ('ix_refresh_expires',   false, '(expires_at)',        NULL),
      ('ix_refresh_clinic',    false, '(clinic_id)',         NULL),
      -- Fase 1
      ('ux_patients_id_clinic',   true,  '(id, clinic_id)',                        NULL),
      ('ux_patients_clinic_cpf',  true,  '(clinic_id, cpf)',                       'cpf IS NOT NULL'),
      ('ix_patients_clinic_name', false, '(clinic_id, name, id)',                  NULL),
      ('ix_patients_created_by',  false, '(created_by, clinic_id)',                NULL),
      ('ix_patients_name_trgm',   false, 'USING gin (lower((name)::text) extensions.gin_trgm_ops)', NULL),
      ('ix_patients_name_prefix', false, '(clinic_id, lower((name)::text) text_pattern_ops)', NULL),
      ('ux_histories_patient',    true,  '(patient_id)',                           NULL),
      ('ix_histories_clinic',     false, '(clinic_id)',                            NULL),
      ('ix_notes_timeline',       false, '(clinic_id, patient_id, noted_at DESC, id DESC)', NULL),
      ('ux_files_storage_key',    true,  '(storage_key)',                          NULL),
      ('ix_files_patient',        false, '(clinic_id, patient_id, created_at DESC, id DESC)', 'deleted_at IS NULL'),
      ('ix_audit_clinic_time',    false, '(clinic_id, occurred_at DESC, id DESC)', NULL),
      ('ix_audit_record',         false, '(clinic_id, table_name, record_id, occurred_at DESC)', NULL),
      -- Fase 2
      ('ex_appt_no_overlap',        false, 'USING gist (clinic_id, tstzrange(starts_at, ends_at))', '(status)::text <> ''CANCELED''::text'),
      ('ix_appt_clinic_start',      false, '(clinic_id, starts_at, id)',                 NULL),
      ('ix_appt_patient',           false, '(clinic_id, patient_id, starts_at DESC, id DESC)', NULL),
      ('ux_appt_id_patient_clinic', true,  '(id, patient_id, clinic_id)',                NULL),
      ('ux_bh_clinic_weekday',      true,  '(clinic_id, weekday)',                       NULL),
      ('ux_clinic_holidays_date',   true,  '(clinic_id, holiday_date)',                  NULL),
      ('ix_notes_appointment',      false, '(clinic_id, appointment_id)',                'appointment_id IS NOT NULL')
    ) AS t(idx, uniq, cols, pred)
  LOOP
    SELECT indexdef INTO def FROM pg_indexes WHERE schemaname = 'core' AND indexname = r.idx;
    IF def IS NULL THEN RAISE EXCEPTION 'indice ausente: %', r.idx; END IF;
    IF (def ILIKE 'CREATE UNIQUE INDEX%') IS DISTINCT FROM r.uniq THEN
      RAISE EXCEPTION 'unicidade divergente em %: %', r.idx, def;
    END IF;
    IF position(r.cols IN def) = 0 THEN
      RAISE EXCEPTION 'colunas divergentes em %: % (esperado %)', r.idx, def, r.cols;
    END IF;
    IF r.pred IS NOT NULL AND (position('WHERE' IN def) = 0 OR position(r.pred IN def) = 0) THEN
      RAISE EXCEPTION 'predicado parcial ausente em %: %', r.idx, def;
    END IF;
  END LOOP;
END $$;

-- Chaves estrangeiras e regras ON DELETE
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_refresh_user_clinic'
                    AND confdeltype = 'c' AND array_length(conkey, 1) = 2) THEN
    RAISE EXCEPTION 'fk_refresh_user_clinic deve ser composta (user_id, clinic_id) com ON DELETE CASCADE';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
                  WHERE t.relname = 'users' AND c.contype = 'f' AND c.confrelid = 'core.clinics'::regclass
                    AND c.confdeltype = 'r') THEN
    RAISE EXCEPTION 'users.clinic_id deve referenciar clinics com ON DELETE RESTRICT';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
                  WHERE t.relname = 'users' AND c.contype = 'f' AND c.confrelid = 'core.users'::regclass
                    AND c.confdeltype = 'n') THEN
    RAISE EXCEPTION 'users.created_by deve referenciar users com ON DELETE SET NULL';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
                  WHERE t.relname = 'refresh_tokens' AND c.contype = 'f'
                    AND c.confrelid = 'core.clinics'::regclass AND c.confdeltype = 'c') THEN
    RAISE EXCEPTION 'refresh_tokens.clinic_id deve referenciar clinics com ON DELETE CASCADE';
  END IF;
END $$;

-- Fase 1: FKs compostas (x, clinic_id) e FKs de clinic_id, todas ON DELETE RESTRICT
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('patients',          'fk_patients_created_by',  'core.users'),
      ('medical_histories', 'fk_histories_patient',    'core.patients'),
      ('medical_histories', 'fk_histories_created_by', 'core.users'),
      ('clinical_notes',    'fk_notes_patient',        'core.patients'),
      ('clinical_notes',    'fk_notes_created_by',     'core.users'),
      ('patient_files',     'fk_files_patient',        'core.patients'),
      ('patient_files',     'fk_files_created_by',     'core.users'),
      ('audit_log',         'fk_audit_user',           'core.users'),
      -- Fase 2
      ('appointments',      'fk_appt_patient',         'core.patients'),
      ('appointments',      'fk_appt_created_by',      'core.users'),
      ('business_hours',    'fk_bh_created_by',        'core.users'),
      ('clinic_holidays',   'fk_chol_created_by',      'core.users')
    ) AS t(tbl, fk, ref)
  LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_constraint c
                    WHERE c.conname = r.fk AND c.conrelid = ('core.' || r.tbl)::regclass
                      AND c.contype = 'f' AND c.confrelid = r.ref::regclass
                      AND c.confdeltype = 'r' AND array_length(c.conkey, 1) = 2) THEN
      RAISE EXCEPTION '% deve ser FK composta para % com ON DELETE RESTRICT', r.fk, r.ref;
    END IF;
  END LOOP;
  FOR r IN SELECT unnest(ARRAY['patients', 'medical_histories', 'clinical_notes', 'patient_files', 'audit_log', 'appointments', 'business_hours', 'clinic_holidays']) AS tbl LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_constraint c
                    WHERE c.conrelid = ('core.' || r.tbl)::regclass AND c.contype = 'f'
                      AND c.confrelid = 'core.clinics'::regclass AND c.confdeltype = 'r') THEN
      RAISE EXCEPTION '%.clinic_id deve referenciar clinics com ON DELETE RESTRICT', r.tbl;
    END IF;
  END LOOP;
  -- audit_log.id e identidade GENERATED ALWAYS
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'core' AND table_name = 'audit_log' AND column_name = 'id'
                    AND is_identity = 'YES' AND identity_generation = 'ALWAYS') THEN
    RAISE EXCEPTION 'audit_log.id deve ser GENERATED ALWAYS AS IDENTITY';
  END IF;
  -- audit_log nao tem coluna de valores (nenhum dado pessoal ou clinico)
  IF (SELECT array_agg(column_name::text ORDER BY column_name) FROM information_schema.columns
       WHERE table_schema = 'core' AND table_name = 'audit_log')
     IS DISTINCT FROM ARRAY['action', 'clinic_id', 'id', 'occurred_at', 'record_id', 'table_name', 'user_id'] THEN
    RAISE EXCEPTION 'audit_log deve ter exatamente as colunas de identificacao do contrato';
  END IF;
END $$;

SELECT 'schema_dictionary: OK' AS resultado;
