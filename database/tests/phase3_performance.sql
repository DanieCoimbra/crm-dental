-- =====================================================================
-- tests/phase3_performance.sql | Fase 3 / F08-phase3-verification (teste 116)
-- Metas do PRD da Fase 3 com 20 clínicas x (5 mil parcelas, 5 mil pagamentos, 1.250 orçamentos): 100 mil parcelas e 100 mil pagamentos
-- no banco. Medido como crm_app (dentista), com a RLS ligada, sem Seq Scan:
--   parcelas vencidas (página de 50) <= 10 ms · soma de recebimentos do mês <= 10 ms · faturamento do período <= 10 ms ·
--   orçamentos do paciente (keyset) <= 5 ms · INSERT de pagamento (guard + status + auditoria) <= 15 ms ·
--   orçamento de 20 itens (criar + 20 itens + desconto + enviar) <= 100 ms.
-- Também sem Seq Scan: itens do orçamento, parcelas do orçamento, ficha financeira do paciente, saldo da parcela e PDF ativo.
-- Carga sintética (revertida no ROLLBACK): triggers de USUÁRIO desligados só durante a carga (FKs e CHECKs seguem valendo), depois ANALYZE.
-- Variável de ambiente CI: se definida, os limites de tempo dobram (máquina compartilhada); o EXPLAIN é sempre impresso.
-- =====================================================================
\set ON_ERROR_STOP on
\set perf_ci ''
\getenv perf_ci CI
SELECT CASE WHEN :'perf_ci' <> '' THEN 2 ELSE 1 END AS perf_factor \gset

\o /dev/null
BEGIN;
\ir support/phase1_seed.sql

-- Clínica n=1 é a A e n=2 é a B do seed; n=3..20 são criadas aqui
CREATE TEMP TABLE perf_clinics AS
SELECT n,
       CASE n WHEN 1 THEN :'ca'::uuid WHEN 2 THEN :'cb'::uuid ELSE ('f5000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid END AS clinic,
       CASE n WHEN 1 THEN :'ua_d'::uuid WHEN 2 THEN :'ub_d'::uuid ELSE ('f5700000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid END AS usr
  FROM generate_series(1, 20) n;

INSERT INTO core.clinics (id, name) SELECT clinic, 'Clinica perf3 ' || n FROM perf_clinics WHERE n > 2;
INSERT INTO core.users (id, clinic_id, name, email, password_hash, role, created_by)
SELECT usr, clinic, 'Dentista perf3 ' || n, 'perf3-' || n || '@fase3.test', 'hash', 'DENTIST_ADMIN', NULL FROM perf_clinics WHERE n > 2;

ALTER TABLE core.patients DISABLE TRIGGER USER;
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by)
SELECT ('f5600000-0000-4000-8000-' || lpad((c.n * 100 + p)::text, 12, '0'))::uuid, c.clinic, 'Paciente perf3 ' || c.n || '-' || p,
       DATE '1990-01-01', '11999990000', c.usr
  FROM perf_clinics c, generate_series(0, 9) p;
ALTER TABLE core.patients ENABLE TRIGGER USER;

ALTER TABLE core.procedure_types DISABLE TRIGGER USER;
INSERT INTO core.procedure_types (id, clinic_id, name, base_price, default_duration_minutes, color, created_by)
SELECT ('f5800000-0000-4000-8000-' || lpad(c.n::text, 12, '0'))::uuid, c.clinic, 'Procedimento perf', 400.00, 30, '#3366FF', c.usr FROM perf_clinics c;
ALTER TABLE core.procedure_types ENABLE TRIGGER USER;

-- 1.250 orçamentos APPROVED por clínica (total 400), 1 item e 1 PDF cada; decididos de 2022 em diante (um a cada 2 dias)
ALTER TABLE core.budgets DISABLE TRIGGER USER;
INSERT INTO core.budgets (id, clinic_id, number, patient_id, status, discount_amount, total_amount, sent_at, decided_at, created_by, created_at, updated_at)
SELECT ('f5100000-0000-4000-8000-' || lpad((c.n * 100000 + g)::text, 12, '0'))::uuid, c.clinic, g + 1,
       ('f5600000-0000-4000-8000-' || lpad((c.n * 100 + (g % 10))::text, 12, '0'))::uuid,
       CASE WHEN g < 1250 THEN 'APPROVED' WHEN g < 1400 THEN 'SENT' ELSE 'DRAFT' END, 0, 400.00,
       CASE WHEN g < 1400 THEN timestamptz '2022-01-03 09:00+00' + g * interval '2 days' END,
       CASE WHEN g < 1250 THEN timestamptz '2022-01-03 10:00+00' + g * interval '2 days' END, c.usr,
       timestamptz '2022-01-03 08:00+00' + g * interval '2 days', timestamptz '2022-01-03 10:00+00' + g * interval '2 days'
  FROM perf_clinics c, generate_series(0, 1499) g;
ALTER TABLE core.budgets ENABLE TRIGGER USER;

ALTER TABLE core.budget_items DISABLE TRIGGER USER;
INSERT INTO core.budget_items (id, clinic_id, budget_id, procedure_type_id, description, quantity, unit_price, discount_amount, position)
SELECT ('f5200000-0000-4000-8000-' || lpad((c.n * 100000 + g)::text, 12, '0'))::uuid, c.clinic,
       ('f5100000-0000-4000-8000-' || lpad((c.n * 100000 + g)::text, 12, '0'))::uuid,
       ('f5800000-0000-4000-8000-' || lpad(c.n::text, 12, '0'))::uuid, 'Procedimento perf', 1, 400.00, 0, 1
  FROM perf_clinics c, generate_series(0, 1499) g;
ALTER TABLE core.budget_items ENABLE TRIGGER USER;

ALTER TABLE core.budget_documents DISABLE TRIGGER USER;
INSERT INTO core.budget_documents (id, clinic_id, budget_id, patient_id, revision, storage_key, content_type, size_bytes, sha256, generated_at, created_by)
SELECT ('f5500000-0000-4000-8000-' || lpad((c.n * 100000 + g)::text, 12, '0'))::uuid, c.clinic,
       ('f5100000-0000-4000-8000-' || lpad((c.n * 100000 + g)::text, 12, '0'))::uuid,
       ('f5600000-0000-4000-8000-' || lpad((c.n * 100 + (g % 10))::text, 12, '0'))::uuid, 1,
       c.clinic::text || '/' || ('f5600000-0000-4000-8000-' || lpad((c.n * 100 + (g % 10))::text, 12, '0')) || '/budgets/' ||
         ('f5500000-0000-4000-8000-' || lpad((c.n * 100000 + g)::text, 12, '0')),
       'application/pdf', 20000, repeat('a', 64), timestamptz '2022-01-03 09:00+00' + g * interval '2 days', c.usr
  FROM perf_clinics c, generate_series(0, 1399) g;
ALTER TABLE core.budget_documents ENABLE TRIGGER USER;

-- 4 parcelas de 100 por orçamento = 5.000 por clínica; 60% PAID (pagas inteiras) e 40% OPEN (50 pagos): 1 pagamento por parcela
ALTER TABLE core.installments DISABLE TRIGGER USER;
INSERT INTO core.installments (id, clinic_id, budget_id, patient_id, number, amount, due_date, status, created_by)
SELECT ('f5300000-0000-4000-8000-' || lpad((c.n * 100000 + g * 4 + k)::text, 12, '0'))::uuid, c.clinic,
       ('f5100000-0000-4000-8000-' || lpad((c.n * 100000 + g)::text, 12, '0'))::uuid,
       ('f5600000-0000-4000-8000-' || lpad((c.n * 100 + (g % 10))::text, 12, '0'))::uuid,
       k + 1, 100.00, (DATE '2022-01-03' + g * 2 + (k + 1) * 30), CASE WHEN (g * 4 + k) % 5 < 3 THEN 'PAID' ELSE 'OPEN' END, c.usr
  FROM perf_clinics c, generate_series(0, 1249) g, generate_series(0, 3) k;
ALTER TABLE core.installments ENABLE TRIGGER USER;

ALTER TABLE core.payments DISABLE TRIGGER USER;
INSERT INTO core.payments (id, clinic_id, installment_id, kind, amount, method, paid_on, created_by)
SELECT ('f5400000-0000-4000-8000-' || lpad((c.n * 100000 + g * 4 + k)::text, 12, '0'))::uuid, c.clinic,
       ('f5300000-0000-4000-8000-' || lpad((c.n * 100000 + g * 4 + k)::text, 12, '0'))::uuid,
       'PAYMENT', CASE WHEN (g * 4 + k) % 5 < 3 THEN 100.00 ELSE 50.00 END, 'PIX',
       (DATE '2022-01-03' + g * 2 + (k + 1) * 30) - CASE WHEN (g * 4 + k) % 5 < 3 THEN 0 ELSE 3 END, c.usr
  FROM perf_clinics c, generate_series(0, 1249) g, generate_series(0, 3) k;
ALTER TABLE core.payments ENABLE TRIGGER USER;

ANALYZE core.patients; ANALYZE core.procedure_types; ANALYZE core.budgets; ANALYZE core.budget_items;
ANALYZE core.budget_documents; ANALYZE core.installments; ANALYZE core.payments;
-- Armazenamento SSD (Supabase e o postgres:16 do harness em disco rápido): o padrão 4 do random_page_cost é de disco rotativo
SET LOCAL random_page_cost = 1.1;

DO $$
BEGIN
  IF (SELECT count(*) FROM core.installments) <> 100000 OR (SELECT count(*) FROM core.payments) <> 100000
     OR (SELECT count(*) FROM core.installments WHERE clinic_id = 'f1000000-0000-4000-8000-00000000000a') <> 5000
     OR (SELECT count(*) FROM core.payments WHERE clinic_id = 'f1000000-0000-4000-8000-00000000000a') <> 5000 THEN
    RAISE EXCEPTION 'T116: carga incorreta (esperadas 100000 parcelas e 100000 pagamentos; 5000 de cada na clinica A)';
  END IF;
END $$;

-- EXPLAIN ANALYZE n vezes (a primeira aquece o cache), confere o plano e o tempo médio das demais. Proibidos no plano: p_forbid.
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
      RAISE NOTICE E'T116 % (plano):
%', p_label, plan;
      FOREACH f IN ARRAY p_forbid LOOP
        IF position(f IN plan) > 0 THEN
          RAISE EXCEPTION 'T116 %: o plano contem "%":%', p_label, f, E'\n' || plan;
        END IF;
      END LOOP;
    ELSE
      total := total + ms;
    END IF;
  END LOOP;
  total := total / runs;
  IF total > p_max_ms THEN
    RAISE EXCEPTION 'T116 %: media de % ms excede % ms', p_label, round(total, 3), p_max_ms;
  END IF;
  RAISE NOTICE 'T116 %: media % ms (limite % ms)', p_label, round(total, 3), p_max_ms;
  RETURN total;
END $$;

\set p_one 'f5600000-0000-4000-8000-000000000103'
\set b_one 'f5100000-0000-4000-8000-000000100003'
\set i_one 'f5300000-0000-4000-8000-000000100012'

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
\o

-- Parcelas vencidas (contas a receber em atraso): página de 50 por vencimento; o backend informa "hoje"
SELECT pg_temp.check_plan('parcelas vencidas (pagina de 50)',
  format($q$SELECT id, due_date, amount FROM core.installments WHERE clinic_id = %L AND status = 'OPEN' AND due_date < DATE '2026-06-01' ORDER BY due_date, id LIMIT 50$q$, :'ca'),
  10 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
SELECT pg_temp.check_plan('contas a receber de um intervalo',
  format($q$SELECT id, due_date, amount FROM core.installments WHERE clinic_id = %L AND status = 'OPEN' AND due_date >= DATE '2025-01-01' AND due_date < DATE '2025-02-01' ORDER BY due_date, id$q$, :'ca'),
  10 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
-- Entradas do mês / fluxo de caixa (regime de caixa): líquido por paid_on
SELECT pg_temp.check_plan('soma de recebimentos do mes',
  format($q$SELECT COALESCE(sum(CASE kind WHEN 'PAYMENT' THEN amount ELSE -amount END), 0) FROM core.payments WHERE clinic_id = %L AND paid_on >= DATE '2025-06-01' AND paid_on < DATE '2025-07-01'$q$, :'ca'),
  10 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
-- Faturamento do período (competência): orçamentos APPROVED por decided_at
SELECT pg_temp.check_plan('faturamento do periodo',
  format($q$SELECT COALESCE(sum(total_amount), 0) FROM core.budgets WHERE clinic_id = %L AND status = 'APPROVED' AND decided_at >= TIMESTAMPTZ '2025-06-01 00:00+00' AND decided_at < TIMESTAMPTZ '2025-07-01 00:00+00'$q$, :'ca'),
  10 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
-- Orçamentos do paciente (keyset por (created_at, id))
SELECT pg_temp.check_plan('orcamentos do paciente (keyset)',
  format($q$SELECT id, number, status, total_amount FROM core.budgets WHERE clinic_id = %L AND patient_id = %L AND (created_at, id) < (TIMESTAMPTZ '2025-01-01 00:00+00', 'ffffffff-ffff-ffff-ffff-ffffffffffff') ORDER BY created_at DESC, id DESC LIMIT 20$q$, :'ca', :'p_one'),
  5 * :perf_factor, ARRAY['Seq Scan', 'Sort']) AS ms \gset
SELECT pg_temp.check_plan('orcamentos por status',
  format($q$SELECT id, number, total_amount FROM core.budgets WHERE clinic_id = %L AND status = 'SENT' ORDER BY created_at DESC, id DESC LIMIT 20$q$, :'ca'),
  10 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
-- itens e parcelas de um orçamento, ficha financeira do paciente, saldo da parcela, PDF ativo
SELECT pg_temp.check_plan('itens do orcamento',
  format($q$SELECT id, description, quantity, line_total FROM core.budget_items WHERE budget_id = %L ORDER BY position$q$, :'b_one'),
  5 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
SELECT pg_temp.check_plan('parcelas do orcamento',
  format($q$SELECT id, number, amount, due_date, status FROM core.installments WHERE budget_id = %L ORDER BY number$q$, :'b_one'),
  5 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
SELECT pg_temp.check_plan('ficha financeira do paciente',
  format($q$SELECT id, due_date, amount, status FROM core.installments WHERE clinic_id = %L AND patient_id = %L ORDER BY due_date, id LIMIT 50$q$, :'ca', :'p_one'),
  10 * :perf_factor, ARRAY['Seq Scan', 'Sort']) AS ms \gset
SELECT pg_temp.check_plan('saldo da parcela (liquido pago)',
  format($q$SELECT COALESCE(sum(CASE kind WHEN 'PAYMENT' THEN amount ELSE -amount END), 0) FROM core.payments WHERE clinic_id = %L AND installment_id = %L$q$, :'ca', :'i_one'),
  5 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
SELECT pg_temp.check_plan('PDF ativo do orcamento',
  format($q$SELECT id, revision, storage_key FROM core.budget_documents WHERE budget_id = %L AND deleted_at IS NULL ORDER BY revision DESC LIMIT 1$q$, :'b_one'),
  5 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset
SELECT pg_temp.check_plan('CR006: parcela OPEN do paciente',
  format($q$SELECT 1 FROM core.installments i WHERE i.clinic_id = %L AND i.patient_id = %L AND i.status = 'OPEN'$q$, :'ca', :'p_one'),
  10 * :perf_factor, ARRAY['Seq Scan']) AS ms \gset

-- ---------------------------------------------------------------------
-- Escritas reais com todos os triggers ligados (200 mil linhas nas tabelas financeiras)
-- ---------------------------------------------------------------------
\o /dev/null
:ctx_a_d \gset
SELECT set_config('perf.factor', :'perf_factor', true) AS _ \gset
\o
DO $$
DECLARE
  t0 timestamptz; ms numeric; k integer;
  f numeric := coalesce(nullif(current_setting('perf.factor', true), ''), '1')::numeric;
  v_i uuid; v_b uuid;
  c constant uuid := 'f1000000-0000-4000-8000-00000000000a';
  u constant uuid := 'f1000000-0000-4000-8000-0000000000a1';
  p constant uuid := 'f5600000-0000-4000-8000-000000000103';
  pt constant uuid := 'f5800000-0000-4000-8000-000000000001';
  total numeric := 0; runs constant integer := 10;
BEGIN
  -- INSERT de pagamento (guard + trava da parcela + saldo + status + auditoria) numa parcela OPEN com saldo
  FOR k IN 1 .. runs LOOP
    SELECT id INTO v_i FROM core.installments WHERE clinic_id = c AND status = 'OPEN' AND due_date > DATE '2025-01-01'
     ORDER BY due_date, id OFFSET k LIMIT 1;
    t0 := clock_timestamp();
    INSERT INTO core.payments (clinic_id, installment_id, kind, amount, method, paid_on, created_by) VALUES (c, v_i, 'PAYMENT', 10.00, 'PIX', DATE '2031-07-01', u);
    ms := extract(epoch FROM clock_timestamp() - t0) * 1000;
    total := total + ms;
  END LOOP;
  total := total / runs;
  RAISE NOTICE 'T116 INSERT de pagamento (media de % escritas): % ms (limite % ms)', runs, round(total, 3), 15 * f;
  IF total > 15 * f THEN RAISE EXCEPTION 'T116: INSERT de pagamento levou % ms (limite % ms)', round(total, 3), 15 * f; END IF;

  -- orçamento de 20 itens: cria, 20 itens (recálculo a cada um), desconto e envio
  t0 := clock_timestamp();
  INSERT INTO core.budgets (clinic_id, patient_id, created_by) VALUES (c, p, u) RETURNING id INTO v_b;
  INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position)
    SELECT c, v_b, pt, 1, g FROM generate_series(1, 20) g;
  UPDATE core.budgets SET discount_amount = 100 WHERE id = v_b;
  UPDATE core.budgets SET status = 'SENT' WHERE id = v_b;
  ms := extract(epoch FROM clock_timestamp() - t0) * 1000;
  RAISE NOTICE 'T116 orcamento de 20 itens (criar + itens + desconto + enviar): % ms (limite % ms)', round(ms, 3), 100 * f;
  IF ms > 100 * f THEN RAISE EXCEPTION 'T116: orcamento de 20 itens levou % ms (limite % ms)', round(ms, 3), 100 * f; END IF;
  IF (SELECT total_amount FROM core.budgets WHERE id = v_b) <> 20 * 400.00 - 100 THEN RAISE EXCEPTION 'T116: total do orcamento de 20 itens incorreto'; END IF;
END $$;

RESET SESSION AUTHORIZATION;
ROLLBACK;
SELECT 'phase3_performance: OK' AS resultado;
