-- =====================================================================
-- tests/phase4_performance.sql | Fase 4 / F05-dashboard-indexes e F02 (teste 137)
-- Metas da spec global da Fase 4 (6.2) com a clínica A com 20 mil pacientes, 100 mil agendamentos, 1.500 orçamentos APPROVED e
-- 100 mil documentos clínicos (a clínica B só tem a semente). Medido como crm_app (dentista), com a RLS ligada:
--   K1 a K5 do dashboard <= 50 ms cada (K2, K3, K4 e K5 sem Seq Scan; K4 com a sonda em ix_appt_completed_patient);
--   lista de documentos do paciente (keyset) <= 10 ms, sem Seq Scan nem Sort; INSERT de documento <= 20 ms;
--   record_document_download <= 10 ms; anonimização de paciente com 50 documentos <= 100 ms.
-- Carga sintética (revertida no ROLLBACK): triggers de USUÁRIO desligados só durante a carga (FKs e CHECKs seguem valendo), depois ANALYZE.
-- Variável de ambiente CI: se definida, os limites de tempo dobram (máquina compartilhada); o EXPLAIN é sempre impresso.
-- =====================================================================
\set ON_ERROR_STOP on
\set perf_ci ''
\getenv perf_ci CI
SELECT CASE WHEN :'perf_ci' <> '' THEN 2 ELSE 1 END AS perf_factor \gset

\o /dev/null
BEGIN;
\ir support/phase4_seed.sql

-- 20 mil pacientes na clínica A (ids determinísticos)
ALTER TABLE core.patients DISABLE TRIGGER USER;
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, active, deactivated_at, created_by)
SELECT md5('pt' || g)::uuid, :'ca', 'Paciente perf4 ' || g, DATE '1990-01-01', '11999990000', (g % 20 <> 0),
       CASE WHEN g % 20 = 0 THEN now() END, :'ua_d'
  FROM generate_series(0, 19999) g;
ALTER TABLE core.patients ENABLE TRIGGER USER;

-- 100 mil agendamentos em fatias de 30 minutos, sem sobreposição, a partir de 2022-01-03: 70% COMPLETED, 10% CANCELED, 10% NO_SHOW, 10% SCHEDULED
ALTER TABLE core.appointments DISABLE TRIGGER USER;
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by)
SELECT md5('ap' || g)::uuid, :'ca', md5('pt' || (g % 20000))::uuid,
       timestamptz '2022-01-03 08:00+00' + g * interval '30 minutes', timestamptz '2022-01-03 08:30+00' + g * interval '30 minutes',
       CASE g % 10 WHEN 7 THEN 'CANCELED' WHEN 8 THEN 'NO_SHOW' WHEN 9 THEN 'SCHEDULED' ELSE 'COMPLETED' END, :'ua_d'
  FROM generate_series(0, 99999) g;
ALTER TABLE core.appointments ENABLE TRIGGER USER;

-- 1.500 orçamentos APPROVED (total 400), um a cada 1,3 dia a partir de 2022
ALTER TABLE core.budgets DISABLE TRIGGER USER;
INSERT INTO core.budgets (id, clinic_id, number, patient_id, status, discount_amount, total_amount, sent_at, decided_at, created_by, created_at, updated_at)
SELECT md5('bd' || g)::uuid, :'ca', g + 1, md5('pt' || (g % 20000))::uuid, 'APPROVED', 0, 400.00,
       timestamptz '2022-01-03 09:00+00' + g * interval '31 hours', timestamptz '2022-01-03 10:00+00' + g * interval '31 hours', :'ua_d',
       timestamptz '2022-01-03 08:00+00' + g * interval '31 hours', timestamptz '2022-01-03 10:00+00' + g * interval '31 hours'
  FROM generate_series(0, 1499) g;
ALTER TABLE core.budgets ENABLE TRIGGER USER;

-- 100 mil documentos: 3 tipos em rodízio, numeração própria por tipo
ALTER TABLE core.clinical_documents DISABLE TRIGGER USER;
INSERT INTO core.clinical_documents (id, clinic_id, patient_id, document_type, number, issued_on, rest_days, target_specialty, content, sha256, created_by, created_at, updated_at)
SELECT md5('cd' || g)::uuid, :'ca', md5('pt' || (g % 20000))::uuid,
       CASE g % 3 WHEN 0 THEN 'CERTIFICATE' WHEN 1 THEN 'PRESCRIPTION' ELSE 'REFERRAL' END,
       g / 3 + 1, DATE '2022-01-03' + (g / 100),
       CASE WHEN g % 3 = 0 THEN 5 END, CASE WHEN g % 3 = 2 THEN 'Endodontia' END,
       repeat('QUJD', 20), repeat('a', 64), :'ua_d',
       timestamptz '2022-01-03 08:00+00' + g * interval '30 minutes', timestamptz '2022-01-03 08:00+00' + g * interval '30 minutes'
  FROM generate_series(0, 99999) g;
ALTER TABLE core.clinical_documents ENABLE TRIGGER USER;

ANALYZE core.patients; ANALYZE core.appointments; ANALYZE core.budgets; ANALYZE core.clinical_documents;
-- Armazenamento SSD (Supabase e o postgres:16 do harness em disco rápido): o padrão 4 do random_page_cost é de disco rotativo
SET LOCAL random_page_cost = 1.1;

DO $$
BEGIN
  IF (SELECT count(*) FROM core.appointments WHERE clinic_id = 'f1000000-0000-4000-8000-00000000000a') <> 100000
     OR (SELECT count(*) FROM core.clinical_documents) <> 100000
     OR (SELECT count(*) FROM core.patients WHERE clinic_id = 'f1000000-0000-4000-8000-00000000000a' AND name LIKE 'Paciente perf4%') <> 20000 THEN
    RAISE EXCEPTION 'T137: carga incorreta';
  END IF;
END $$;

-- EXPLAIN ANALYZE n vezes (a primeira aquece o cache), confere o plano e o tempo médio das demais.
-- Proibidos no plano: p_forbid. Exigidos no plano: p_require (trecho de texto).
CREATE FUNCTION pg_temp.check_plan(p_label text, p_sql text, p_max_ms numeric, p_forbid text[] DEFAULT ARRAY['Seq Scan'], p_require text DEFAULT NULL)
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
      RAISE NOTICE E'T137 % (plano):
%', p_label, plan;
      FOREACH f IN ARRAY p_forbid LOOP
        IF position(f IN plan) > 0 THEN
          RAISE EXCEPTION 'T137 %: o plano contem "%":%', p_label, f, E'\n' || plan;
        END IF;
      END LOOP;
      IF p_require IS NOT NULL AND position(p_require IN plan) = 0 THEN
        RAISE EXCEPTION 'T137 %: o plano deveria conter "%":%', p_label, p_require, E'\n' || plan;
      END IF;
    ELSE
      total := total + ms;
    END IF;
  END LOOP;
  total := total / runs;
  IF total > p_max_ms THEN
    RAISE EXCEPTION 'T137 %: media de % ms excede % ms', p_label, round(total, 3), p_max_ms;
  END IF;
  RAISE NOTICE 'T137 %: media % ms (limite % ms)', p_label, round(total, 3), p_max_ms;
  RETURN total;
END $$;

\set p_one 'f1000000-0000-4000-8000-000000000001'
SELECT md5('pt' || 123)::uuid AS p_one \gset

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
\o

-- K1 total de pacientes (sem exigir Index Scan: a maioria das linhas casa; a meta é o tempo)
SELECT pg_temp.check_plan('K1 total de pacientes',
  format($q$SELECT count(*) FROM core.patients WHERE clinic_id = %L AND active = true$q$, :'ca'),
  50 * :perf_factor, ARRAY[]::text[]) AS ms \gset
-- K2 consultas hoje
SELECT pg_temp.check_plan('K2 consultas hoje',
  format($q$SELECT count(*) FROM core.appointments WHERE clinic_id = %L AND starts_at >= TIMESTAMPTZ '2025-06-10 00:00+00' AND starts_at < TIMESTAMPTZ '2025-06-11 00:00+00' AND status <> 'CANCELED' AND status <> 'NO_SHOW'$q$, :'ca'),
  50 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
-- K3 faturamento do mês (competência)
SELECT pg_temp.check_plan('K3 faturamento do mes',
  format($q$SELECT COALESCE(sum(total_amount), 0) FROM core.budgets WHERE clinic_id = %L AND status = 'APPROVED' AND decided_at >= TIMESTAMPTZ '2025-06-01 00:00+00' AND decided_at < TIMESTAMPTZ '2025-07-01 00:00+00'$q$, :'ca'),
  50 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
-- K4 taxa de retorno: a sonda por paciente usa o índice parcial
SELECT pg_temp.check_plan('K4 taxa de retorno',
  format($q$WITH m AS (SELECT DISTINCT patient_id FROM core.appointments WHERE clinic_id = %1$L AND status = 'COMPLETED' AND starts_at >= TIMESTAMPTZ '2025-06-01 00:00+00' AND starts_at < TIMESTAMPTZ '2025-07-01 00:00+00')
          SELECT count(*) AS atendidos,
                 count(*) FILTER (WHERE EXISTS (SELECT 1 FROM core.appointments p WHERE p.clinic_id = %1$L AND p.patient_id = m.patient_id AND p.status = 'COMPLETED'
                                                  AND p.starts_at >= TIMESTAMPTZ '2024-06-01 00:00+00' AND p.starts_at < TIMESTAMPTZ '2025-06-01 00:00+00')) AS retornos
            FROM m$q$, :'ca'),
  50 * :perf_factor, ARRAY['Seq Scan'], 'ix_appt_completed_patient') AS ms \gset
-- K5 gráfico semanal
SELECT pg_temp.check_plan('K5 grafico semanal',
  format($q$SELECT (starts_at AT TIME ZONE 'America/Sao_Paulo')::date AS dia, status, count(*) FROM core.appointments WHERE clinic_id = %L AND starts_at >= TIMESTAMPTZ '2025-06-09 03:00+00' AND starts_at < TIMESTAMPTZ '2025-06-16 03:00+00' GROUP BY 1, 2$q$, :'ca'),
  50 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
-- lista de documentos do paciente (keyset)
SELECT pg_temp.check_plan('lista de documentos do paciente (keyset)',
  format($q$SELECT id, number, document_type, created_at FROM core.clinical_documents WHERE clinic_id = %L AND patient_id = %L AND (created_at, id) < (TIMESTAMPTZ '2030-01-01 00:00+00', 'ffffffff-ffff-ffff-ffff-ffffffffffff') ORDER BY created_at DESC, id DESC LIMIT 20$q$, :'ca', :'p_one'),
  10 * :perf_factor, ARRAY['Seq Scan', 'Sort']) AS ms \gset

-- ---------------------------------------------------------------------
-- Escritas reais com todos os triggers ligados (100 mil documentos)
-- ---------------------------------------------------------------------
\o /dev/null
:ctx_a_d \gset
SELECT set_config('perf.factor', :'perf_factor', true) AS _ \gset
\o
DO $$
DECLARE
  t0 timestamptz; ms numeric; k integer;
  f numeric := coalesce(nullif(current_setting('perf.factor', true), ''), '1')::numeric;
  c constant uuid := 'f1000000-0000-4000-8000-00000000000a';
  u constant uuid := 'f1000000-0000-4000-8000-0000000000a1';
  p constant uuid := md5('pt' || 123)::uuid;
  v_doc uuid; v_pat uuid;
  total numeric := 0; runs constant integer := 10;
BEGIN
  -- INSERT de documento (guardas + CRO + número sob lock + auditoria)
  FOR k IN 1 .. runs LOOP
    t0 := clock_timestamp();
    INSERT INTO core.clinical_documents (clinic_id, patient_id, document_type, issued_on, rest_days, content, sha256, created_by)
    VALUES (c, p, 'CERTIFICATE', DATE '2031-07-01', 3, repeat('QUJD', 20), repeat('a', 64), u);
    total := total + extract(epoch FROM clock_timestamp() - t0) * 1000;
  END LOOP;
  total := total / runs;
  RAISE NOTICE 'T137 INSERT de documento (media de % escritas): % ms (limite % ms)', runs, round(total, 3), 20 * f;
  IF total > 20 * f THEN RAISE EXCEPTION 'T137: INSERT de documento levou % ms (limite % ms)', round(total, 3), 20 * f; END IF;

  -- record_document_download
  SELECT id INTO v_doc FROM core.clinical_documents WHERE clinic_id = c AND patient_id = p ORDER BY created_at DESC, id DESC LIMIT 1;
  total := 0;
  FOR k IN 1 .. runs LOOP
    t0 := clock_timestamp();
    PERFORM core.record_document_download(v_doc);
    total := total + extract(epoch FROM clock_timestamp() - t0) * 1000;
  END LOOP;
  total := total / runs;
  RAISE NOTICE 'T137 record_document_download (media de % chamadas): % ms (limite % ms)', runs, round(total, 3), 10 * f;
  IF total > 10 * f THEN RAISE EXCEPTION 'T137: record_document_download levou % ms (limite % ms)', round(total, 3), 10 * f; END IF;

  -- anonimização de um paciente com 50 documentos
  v_pat := gen_random_uuid();
  INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES (v_pat, c, 'Paciente anonimizar perf4', DATE '1980-01-01', '11999990000', u);
  FOR k IN 1 .. 50 LOOP
    INSERT INTO core.clinical_documents (clinic_id, patient_id, document_type, issued_on, rest_days, content, sha256, created_by)
    VALUES (c, v_pat, 'CERTIFICATE', DATE '2031-07-01', 2, repeat('QUJD', 20), repeat('a', 64), u);
  END LOOP;
  UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = v_pat;
  t0 := clock_timestamp();
  PERFORM core.anonymize_patient(v_pat);
  ms := extract(epoch FROM clock_timestamp() - t0) * 1000;
  RAISE NOTICE 'T137 anonimizacao de paciente com 50 documentos: % ms (limite % ms)', round(ms, 3), 100 * f;
  IF ms > 100 * f THEN RAISE EXCEPTION 'T137: anonimizacao levou % ms (limite % ms)', round(ms, 3), 100 * f; END IF;
  IF (SELECT count(*) FROM core.clinical_documents WHERE patient_id = v_pat AND anonymized_at IS NOT NULL AND content IS NULL) <> 50 THEN
    RAISE EXCEPTION 'T137: os 50 documentos deveriam estar anonimizados';
  END IF;
END $$;

RESET SESSION AUTHORIZATION;
ROLLBACK;
SELECT 'phase4_performance: OK' AS resultado;
