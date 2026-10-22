-- =====================================================================
-- tests/performance_search.sql | Fase 1 / F02-patients, F03, F04, F05 (teste 55)
-- Meta 4 do PRD da Fase 1: busca de pacientes por trecho do nome ≤ 20 ms e por CPF ≤ 5 ms com 10 mil pacientes
-- na clínica e 100 mil no banco; listagem por keyset, linha do tempo de notas, lista de exames e consultas de
-- auditoria usam índice (sem Seq Scan e sem Sort). Medido como crm_app, com a RLS ligada. Inclui as consultas exatas que o
-- backend emite na busca (LIKE '%q%' ESCAPE, filtro de situação, ORDER BY name, id, LIMIT e o count(*) da página).
-- Carga sintética (revertida no ROLLBACK): 10 mil pacientes na clínica A e 90 mil na B, 2.000 notas e 200 exames
-- em um paciente, 50 mil linhas de auditoria. Os triggers das tabelas ficam desligados só durante a carga.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase1_seed.sql

\set p_big 'f1000000-0000-4000-8000-0000000000c1'

-- Carga (como administrador; ignora a RLS). Trigger de auditoria e demais triggers desligados só aqui.
ALTER TABLE core.patients          DISABLE TRIGGER USER;
ALTER TABLE core.clinical_notes    DISABLE TRIGGER USER;
ALTER TABLE core.patient_files     DISABLE TRIGGER USER;

INSERT INTO core.patients (id, clinic_id, name, cpf, birth_date, phone, created_by)
SELECT CASE WHEN g = 1 THEN :'p_big'::uuid ELSE gen_random_uuid() END, :'ca',
       substr(md5('a' || g::text), 1, 12) || ' ' || substr(md5('b' || g::text), 1, 8),
       lpad(g::text, 11, '0'), DATE '1990-01-01' + (g % 10000), '11999990000', :'ua_d'
  FROM generate_series(1, 10000) g;
INSERT INTO core.patients (id, clinic_id, name, cpf, birth_date, phone, created_by)
SELECT gen_random_uuid(), :'cb',
       substr(md5('c' || g::text), 1, 12) || ' ' || substr(md5('d' || g::text), 1, 8),
       lpad((g + 1000000)::text, 11, '0'), DATE '1990-01-01' + (g % 10000), '11999990000', :'ub_d'
  FROM generate_series(1, 90000) g;

INSERT INTO core.clinical_notes (clinic_id, patient_id, noted_at, content, created_by)
SELECT :'ca', :'p_big', now() - g * interval '1 hour', repeat('QUJD', 12), :'ua_d' FROM generate_series(1, 2000) g;
INSERT INTO core.patient_files (clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by, created_at)
SELECT :'ca', :'p_big', 'OTHER', :'ca' || '/' || :'p_big' || '/' || u, 'arquivo.pdf', 'application/pdf', 100, :'ua_d', now() - g * interval '1 hour'
  FROM (SELECT g, gen_random_uuid()::text AS u FROM generate_series(1, 200) g) q;
INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action, occurred_at)
SELECT :'ca', :'ua_d', 'patients', CASE WHEN g % 500 = 0 THEN :'p_big'::uuid ELSE gen_random_uuid() END, 'UPDATE', now() - g * interval '1 minute'
  FROM generate_series(1, 50000) g;

ALTER TABLE core.patients       ENABLE TRIGGER USER;
ALTER TABLE core.clinical_notes ENABLE TRIGGER USER;
ALTER TABLE core.patient_files  ENABLE TRIGGER USER;
ANALYZE core.patients;
ANALYZE core.clinical_notes;
ANALYZE core.patient_files;
ANALYZE core.audit_log;
-- Equivale ao autovacuum em produção: esvazia a fila pendente do GIN (fastupdate) deixada pela carga em massa
SELECT gin_clean_pending_list('core.ix_patients_name_trgm'::regclass) AS _ \gset

-- Executa EXPLAIN ANALYZE n vezes (a primeira aquece o cache), confere o plano e o tempo médio das demais.
-- Proibidos no plano: p_forbid (por padrão Seq Scan e Sort). Devolve o tempo médio em ms.
CREATE FUNCTION pg_temp.check_plan(p_label text, p_sql text, p_max_ms numeric, p_forbid text[] DEFAULT ARRAY['Seq Scan', 'Sort'])
  RETURNS numeric LANGUAGE plpgsql AS $$
DECLARE
  line text; plan text; ms numeric; total numeric := 0; i int; f text;
  runs constant int := 10;
BEGIN
  FOR i IN 0 .. runs LOOP
    plan := '';
    FOR line IN EXECUTE 'EXPLAIN (ANALYZE, COSTS OFF, TIMING ON, SUMMARY ON) ' || p_sql LOOP
      plan := plan || line || E'\n';
      IF line LIKE 'Execution Time:%' THEN ms := substring(line FROM '([0-9.]+) ms')::numeric; END IF;
    END LOOP;
    IF i = 0 THEN
      RAISE NOTICE E'T55 % (plano):
%', p_label, plan;
      FOREACH f IN ARRAY p_forbid LOOP
        IF position(f IN plan) > 0 THEN
          RAISE EXCEPTION 'T55 %: o plano contem "%":%', p_label, f, E'\n' || plan;
        END IF;
      END LOOP;
    ELSE
      total := total + ms;
    END IF;
  END LOOP;
  total := total / runs;
  IF total > p_max_ms THEN
    RAISE EXCEPTION 'T55 %: media de % ms excede % ms', p_label, round(total, 3), p_max_ms;
  END IF;
  RAISE NOTICE 'T55 %: media % ms (limite % ms)', p_label, round(total, 3), p_max_ms;
  RETURN total;
END $$;

-- Termos reais para as buscas
SELECT name AS sample_name, cpf AS sample_cpf FROM core.patients WHERE clinic_id = :'ca' ORDER BY id OFFSET 4321 LIMIT 1 \gset
SELECT lower(substr(:'sample_name', 3, 6)) AS term, lower(substr(:'sample_name', 1, 2)) AS prefix \gset

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
\o
SELECT pg_temp.check_plan('busca por trecho do nome', format('SELECT id, name FROM core.patients WHERE lower(name) LIKE %L LIMIT 50', '%' || :'term' || '%'), 20,
                          ARRAY['Seq Scan']) AS ms \gset
SELECT pg_temp.check_plan('busca por prefixo (2 caracteres)', format('SELECT id, name FROM core.patients WHERE lower(name) LIKE %L LIMIT 50', :'prefix' || '%'), 20,
                          ARRAY['Seq Scan']) AS ms \gset
SELECT pg_temp.check_plan('busca por CPF', format('SELECT id, name FROM core.patients WHERE cpf = %L', :'sample_cpf'), 5) AS ms \gset
SELECT pg_temp.check_plan('listagem por keyset (name, id)', $q$SELECT id, name FROM core.patients WHERE (name, id) > ('5', '00000000-0000-0000-0000-000000000000') ORDER BY name, id LIMIT 20$q$, 20) AS ms \gset
SELECT pg_temp.check_plan('linha do tempo de notas',
  format($q$SELECT id, noted_at FROM core.clinical_notes WHERE patient_id = %L AND (noted_at, id) < (now(), 'ffffffff-ffff-ffff-ffff-ffffffffffff') ORDER BY noted_at DESC, id DESC LIMIT 20$q$, :'p_big'), 20) AS ms \gset
SELECT pg_temp.check_plan('lista de exames ativos',
  format($q$SELECT id FROM core.patient_files WHERE patient_id = %L AND deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT 20$q$, :'p_big'), 20) AS ms \gset
SELECT pg_temp.check_plan('auditoria de um registro',
  format($q$SELECT id FROM core.audit_log WHERE table_name = 'patients' AND record_id = %L ORDER BY occurred_at DESC LIMIT 20$q$, :'p_big'), 20) AS ms \gset
SELECT pg_temp.check_plan('auditoria recente da clinica',
  $q$SELECT id FROM core.audit_log ORDER BY occurred_at DESC, id DESC LIMIT 50$q$, 20) AS ms \gset

-- Consultas REAIS da busca do backend (PatientService.search): Hibernate emite lower(name) LIKE '%q%' ESCAPE '\' (também
-- para 1-2 caracteres), filtro de situação, ORDER BY name, id e LIMIT de uma página, mais o count(*) da Page. Os testes
-- acima medem a forma ideal (prefixo / trecho sem ordem); estes medem o que a API de fato executa, sob a mesma RLS.
SELECT pg_temp.check_plan('API: trecho de 2 caracteres (contem), ativos, ordenado',
  format($q$SELECT id, name FROM core.patients WHERE lower(name) LIKE %L ESCAPE %L AND active = true ORDER BY name, id LIMIT 20$q$,
         '%' || :'prefix' || '%', E'\\'), 20, ARRAY['Seq Scan']) AS ms \gset
SELECT pg_temp.check_plan('API: trecho de 3+ caracteres, ativos, ordenado',
  format($q$SELECT id, name FROM core.patients WHERE lower(name) LIKE %L ESCAPE %L AND active = true ORDER BY name, id LIMIT 20$q$,
         '%' || :'term' || '%', E'\\'), 20, ARRAY['Seq Scan']) AS ms \gset
SELECT pg_temp.check_plan('API: count(*) do trecho de 2 caracteres',
  format($q$SELECT count(*) FROM core.patients WHERE lower(name) LIKE %L ESCAPE %L AND active = true$q$,
         '%' || :'prefix' || '%', E'\\'), 20, ARRAY['Seq Scan']) AS ms \gset
SELECT pg_temp.check_plan('API: count(*) do trecho de 3+ caracteres',
  format($q$SELECT count(*) FROM core.patients WHERE lower(name) LIKE %L ESCAPE %L AND active = true$q$,
         '%' || :'term' || '%', E'\\'), 20, ARRAY['Seq Scan']) AS ms \gset

RESET SESSION AUTHORIZATION;
ROLLBACK;
SELECT 'performance_search: OK' AS resultado;
