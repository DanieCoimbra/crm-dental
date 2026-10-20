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
 ('refresh_tokens','created_at','timestamp with time zone',NULL,'NO','now()');

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
     WHERE c.table_schema = 'core' AND c.table_name IN ('clinics', 'users', 'refresh_tokens')
       AND NOT EXISTS (SELECT 1 FROM expected_cols e WHERE e.tbl = c.table_name AND e.col = c.column_name)
  ) x;
  IF v IS NOT NULL THEN
    RAISE EXCEPTION E'dicionario divergente:\n%', v;
  END IF;

  -- FR-9: nenhuma coluna de plano/trial/cobrança/expiração de conta
  IF EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = 'core' AND table_name IN ('clinics', 'users', 'refresh_tokens')
                AND column_name ~* '(plan|trial|billing|subscription|account_expir|paid)') THEN
    RAISE EXCEPTION 'FR-9: coluna de plano/trial/cobranca encontrada';
  END IF;

  -- Nenhum char(n)/text nas tabelas (compatibilidade com o ddl-auto=validate)
  IF EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = 'core' AND table_name IN ('clinics', 'users', 'refresh_tokens')
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
      ('ix_refresh_clinic',    false, '(clinic_id)',         NULL)
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

SELECT 'schema_dictionary: OK' AS resultado;
