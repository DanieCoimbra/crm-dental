-- =====================================================================
-- tests/phase2_performance.sql | Fase 2 / F08-phase2-verification (teste 81)
-- Meta 5 do PRD da Fase 2: agenda da semana <= 10 ms e checagem de conflito <= 5 ms com 20 mil agendamentos na clínica e
-- 200 mil no banco, sem Seq Scan (a agenda também sem Sort). Medido como crm_app, com a RLS ligada. Também: histórico do
-- paciente (keyset) e o EXISTS do CR004 usam índice.
-- Carga sintética (revertida no ROLLBACK): 10 clínicas x 20 mil agendamentos (8 por dia, de 30 min, sem sobreposição, ~2.500
-- dias; 2% cancelados) e 10 pacientes por clínica. Triggers de usuário desligados só durante a carga (o EXCLUDE segue valendo).
-- Variável de ambiente CI: se definida, os limites de tempo dobram (máquina compartilhada); o EXPLAIN é sempre impresso.
-- =====================================================================
\set ON_ERROR_STOP on
\set perf_ci ''
\getenv perf_ci CI
SELECT CASE WHEN :'perf_ci' <> '' THEN 2 ELSE 1 END AS perf_factor \gset

\o /dev/null
BEGIN;
\ir support/phase1_seed.sql

-- Clínica n=1 é a A e n=2 é a B do seed; n=3..10 são criadas aqui
CREATE TEMP TABLE perf_clinics AS
SELECT n,
       CASE n WHEN 1 THEN :'ca'::uuid WHEN 2 THEN :'cb'::uuid ELSE ('f3000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid END AS clinic,
       CASE n WHEN 1 THEN :'ua_d'::uuid WHEN 2 THEN :'ub_d'::uuid ELSE ('f3200000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid END AS usr
  FROM generate_series(1, 10) n;

INSERT INTO core.clinics (id, name) SELECT clinic, 'Clinica perf ' || n FROM perf_clinics WHERE n > 2;
INSERT INTO core.users (id, clinic_id, name, email, password_hash, role, created_by)
SELECT usr, clinic, 'Dentista perf ' || n, 'perf' || n || '@fase2.test', 'hash', 'DENTIST_ADMIN', NULL FROM perf_clinics WHERE n > 2;

ALTER TABLE core.patients DISABLE TRIGGER USER;
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by)
SELECT ('f3100000-0000-4000-8000-' || lpad((c.n * 100 + p)::text, 12, '0'))::uuid, c.clinic, 'Paciente perf ' || c.n || '-' || p,
       DATE '1990-01-01', '11999990000', c.usr
  FROM perf_clinics c, generate_series(0, 9) p;
ALTER TABLE core.patients ENABLE TRIGGER USER;

-- 20 mil por clínica: 8 por dia (uma por hora, 30 min) durante 2.500 dias a partir de 2022-01-03
ALTER TABLE core.appointments DISABLE TRIGGER USER;
INSERT INTO core.appointments (clinic_id, patient_id, starts_at, ends_at, status, created_by)
SELECT c.clinic,
       ('f3100000-0000-4000-8000-' || lpad((c.n * 100 + (g % 10))::text, 12, '0'))::uuid,
       timestamptz '2022-01-03 08:00+00' + (g / 8) * interval '1 day' + (g % 8) * interval '1 hour',
       timestamptz '2022-01-03 08:30+00' + (g / 8) * interval '1 day' + (g % 8) * interval '1 hour',
       CASE WHEN g % 50 = 0 THEN 'CANCELED' ELSE 'COMPLETED' END,
       c.usr
  FROM perf_clinics c, generate_series(0, 19999) g;
ALTER TABLE core.appointments ENABLE TRIGGER USER;
ANALYZE core.appointments;
ANALYZE core.patients;
-- Armazenamento SSD (Supabase e o postgres:16 do harness em disco rápido): o padrão 4 do random_page_cost é de disco rotativo e
-- faz o planejador preferir Bitmap + Sort a um Index Scan já ordenado em 56 linhas.
SET LOCAL random_page_cost = 1.1;

DO $$
BEGIN
  IF (SELECT count(*) FROM core.appointments) <> 200000
     OR (SELECT count(*) FROM core.appointments WHERE clinic_id = 'f1000000-0000-4000-8000-00000000000a') <> 20000 THEN
    RAISE EXCEPTION 'T81: carga incorreta (esperados 200000 no total e 20000 na clinica A)';
  END IF;
END $$;

-- Executa EXPLAIN ANALYZE n vezes (a primeira aquece o cache), confere o plano e o tempo médio das demais.
-- Proibidos no plano: p_forbid (por padrão Seq Scan). Devolve o tempo médio em ms.
CREATE FUNCTION pg_temp.check_plan(p_label text, p_sql text, p_max_ms numeric, p_forbid text[] DEFAULT ARRAY['Seq Scan'])
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
      RAISE NOTICE E'T81 % (plano):
%', p_label, plan;
      FOREACH f IN ARRAY p_forbid LOOP
        IF position(f IN plan) > 0 THEN
          RAISE EXCEPTION 'T81 %: o plano contem "%":%', p_label, f, E'\n' || plan;
        END IF;
      END LOOP;
    ELSE
      total := total + ms;
    END IF;
  END LOOP;
  total := total / runs;
  IF total > p_max_ms THEN
    RAISE EXCEPTION 'T81 %: media de % ms excede % ms', p_label, round(total, 3), p_max_ms;
  END IF;
  RAISE NOTICE 'T81 %: media % ms (limite % ms)', p_label, round(total, 3), p_max_ms;
  RETURN total;
END $$;

\set week_from '2025-06-02 00:00+00'
\set week_to   '2025-06-09 00:00+00'
\set p_big 'f3100000-0000-4000-8000-000000000100'

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
\o

-- Agenda da semana (consulta canônica da spec 4.6: recuo de 12 h em starts_at + ends_at > início): Index Scan, sem Sort
SELECT pg_temp.check_plan('agenda da semana (dentista)',
  format($q$SELECT id, starts_at, ends_at, status FROM core.appointments WHERE clinic_id = %L AND starts_at >= %L::timestamptz - interval '12 hours' AND starts_at < %L::timestamptz AND ends_at > %L::timestamptz ORDER BY starts_at, id$q$,
         :'ca', :'week_from', :'week_to', :'week_from'),
  10 * :perf_factor, ARRAY['Seq Scan', 'Sort']) AS ms \gset
:ctx_a_r \gset
SELECT pg_temp.check_plan('agenda da semana (recepcao)',
  format($q$SELECT id, starts_at, ends_at, status FROM core.appointments WHERE clinic_id = %L AND starts_at >= %L::timestamptz - interval '12 hours' AND starts_at < %L::timestamptz AND ends_at > %L::timestamptz ORDER BY starts_at, id$q$,
         :'ca', :'week_from', :'week_to', :'week_from'),
  10 * :perf_factor, ARRAY['Seq Scan', 'Sort']) AS ms \gset
SELECT pg_temp.check_plan('agenda do dia',
  format($q$SELECT id, starts_at, ends_at, status FROM core.appointments WHERE clinic_id = %L AND starts_at >= %L::timestamptz - interval '12 hours' AND starts_at < %L::timestamptz AND ends_at > %L::timestamptz ORDER BY starts_at, id$q$,
         :'ca', '2025-06-04 00:00+00', '2025-06-05 00:00+00', '2025-06-04 00:00+00'),
  10 * :perf_factor, ARRAY['Seq Scan', 'Sort']) AS ms \gset

-- Checagem de conflito (pré-checagem opcional; a autoridade continua sendo a constraint, cuja checagem é interna e não passa por RLS).
-- ATENÇÃO: sob RLS o operador && de tstzrange NÃO é leakproof e o planejador não o usa como condição de índice para crm_app
-- (tentar o GiST com `tstzrange(starts_at, ends_at) && ...` varre a clínica toda: ~15 ms com 20 mil linhas). A pré-checagem usa
-- a mesma forma da agenda (comparações leakproof + recuo de 12 h do teto de duração): Index Scan estreito em ix_appt_clinic_start.
SELECT pg_temp.check_plan('conflito (pre-checagem por intervalo)',
  format($q$SELECT id FROM core.appointments WHERE clinic_id = %L AND status <> 'CANCELED' AND status <> 'NO_SHOW' AND starts_at >= %L::timestamptz - interval '12 hours' AND starts_at < %L::timestamptz AND ends_at > %L::timestamptz AND id <> %L$q$,
         :'ca', '2025-06-04 08:00+00', '2025-06-04 08:30+00', '2025-06-04 08:00+00', '00000000-0000-0000-0000-000000000000'),
  5 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset

-- Histórico do paciente (keyset por (starts_at, id)) e o EXISTS do CR004
SELECT pg_temp.check_plan('historico do paciente (keyset)',
  format($q$SELECT id, starts_at FROM core.appointments WHERE patient_id = %L AND (starts_at, id) < (%L::timestamptz, 'ffffffff-ffff-ffff-ffff-ffffffffffff') ORDER BY starts_at DESC, id DESC LIMIT 20$q$,
         :'p_big', '2025-06-04 08:00+00'),
  10 * :perf_factor, ARRAY['Seq Scan', 'Sort']) AS ms \gset
SELECT pg_temp.check_plan('CR004: agendamento futuro em aberto do paciente',
  format($q$SELECT 1 FROM core.appointments a WHERE a.patient_id = %L AND (a.status = 'SCHEDULED' OR a.status = 'CONFIRMED' OR a.status = 'IN_PROGRESS') AND a.ends_at > now()$q$, :'p_big'),
  10 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset

-- O INSERT real com a constraint de exclusão e os triggers ligados (200 mil linhas no banco): custo de uma escrita
\o /dev/null
:ctx_a_d \gset
SELECT set_config('perf.factor', :'perf_factor', true) AS _ \gset
\o
DO $$
DECLARE
  t0 timestamptz := clock_timestamp();
  ms numeric;
  lim numeric := 10 * coalesce(nullif(current_setting('perf.factor', true), ''), '1')::numeric;
BEGIN
  INSERT INTO core.appointments (clinic_id, patient_id, starts_at, ends_at, status, created_by)
  VALUES ('f1000000-0000-4000-8000-00000000000a', 'f3100000-0000-4000-8000-000000000100', '2031-01-01 08:00+00', '2031-01-01 08:30+00', 'SCHEDULED',
          'f1000000-0000-4000-8000-0000000000a1');
  ms := extract(epoch FROM clock_timestamp() - t0) * 1000;
  RAISE NOTICE 'T81 INSERT com EXCLUDE, guarda do paciente e auditoria: % ms', round(ms, 3);
  IF ms > lim * 4 THEN RAISE EXCEPTION 'T81: INSERT de agendamento levou % ms', round(ms, 3); END IF;
END $$;

RESET SESSION AUTHORIZATION;
ROLLBACK;
SELECT 'phase2_performance: OK' AS resultado;
