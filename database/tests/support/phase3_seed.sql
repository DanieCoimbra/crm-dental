-- =====================================================================
-- tests/support/phase3_seed.sql | Fase 3 (F01-F08)
-- Apoio dos testes da Fase 3. Incluir com `\ir support/phase3_seed.sql` DENTRO de uma transação (BEGIN) aberta pelo
-- teste, como administrador do banco de teste. Tudo é revertido no ROLLBACK.
--   * traz o seed da Fase 2 (clínicas A e B, dentista e recepção de cada uma, pacientes pa/pa2/pa_off/pa_anon/pb, macros ctx_*,
--     expect_err, expect_rows);
--   * procedimentos: pt1 (Restauracao, 150,00) e pt2 (Canal, 800,00) na clínica A; ptb (Restauracao, 90,00) na B;
--   * construtores de SQL (pg_temp.*_sql) para passar a expect_err / expect_rows;
--   * pg_temp.f3_fixture: orçamento APPROVED (2 x pt, total 300,00) com PDF, uma parcela de 300,00 e um pagamento parcial de 100,00;
--   * pg_temp.expect_err_commit / expect_ok_commit: executam o lote e FORÇAM as constraints adiáveis (SET CONSTRAINTS ALL IMMEDIATE),
--     porque o teste nunca faz COMMIT e a constraint trigger da soma das parcelas só dispara no commit.
-- =====================================================================
\ir phase2_seed.sql

\set pt1 'f4000000-0000-4000-8000-0000000000c1'
\set pt2 'f4000000-0000-4000-8000-0000000000c2'
\set ptb 'f4000000-0000-4000-8000-0000000000c3'

:ctx_a_d \gset
INSERT INTO core.procedure_types (id, clinic_id, name, base_price, default_duration_minutes, color, created_by) VALUES
  (:'pt1', :'ca', 'Restauracao', 150.00, 30, '#3366FF', :'ua_d'),
  (:'pt2', :'ca', 'Canal',       800.00, 60, '#FF6600', :'ua_d');
:ctx_b_d \gset
INSERT INTO core.procedure_types (id, clinic_id, name, base_price, default_duration_minutes, color, created_by) VALUES
  (:'ptb', :'cb', 'Restauracao', 90.00, 30, '#00AA55', :'ub_d');
:ctx_a_d \gset

-- ---------------------------------------------------------------------
-- Construtores de SQL
-- ---------------------------------------------------------------------
CREATE FUNCTION pg_temp.ptype_sql(p_id text, p_clinic text, p_name text, p_price text, p_minutes text, p_color text, p_user text)
  RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.procedure_types (id, clinic_id, name, base_price, default_duration_minutes, color, created_by) '
                'VALUES (%L, %L, %L, %L, %L, %L, %L)', p_id, p_clinic, p_name, p_price, p_minutes, p_color, p_user) $$;

CREATE FUNCTION pg_temp.budget_sql(p_id text, p_clinic text, p_patient text, p_user text) RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.budgets (id, clinic_id, patient_id, created_by) VALUES (%L, %L, %L, %L)', p_id, p_clinic, p_patient, p_user) $$;

CREATE FUNCTION pg_temp.item_sql(p_clinic text, p_budget text, p_proc text, p_qty text, p_pos text, p_disc text DEFAULT '0')
  RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position, discount_amount) '
                'VALUES (%L, %L, %L, %L, %L, %L)', p_clinic, p_budget, p_proc, p_qty, p_pos, p_disc) $$;

CREATE FUNCTION pg_temp.budget_status_sql(p_id text, p_status text) RETURNS text LANGUAGE sql AS $$
  SELECT format('UPDATE core.budgets SET status = %L WHERE id = %L', p_status, p_id) $$;

CREATE FUNCTION pg_temp.budget_discount_sql(p_id text, p_discount text) RETURNS text LANGUAGE sql AS $$
  SELECT format('UPDATE core.budgets SET discount_amount = %L WHERE id = %L', p_discount, p_id) $$;

CREATE FUNCTION pg_temp.doc_sql(p_clinic text, p_budget text, p_patient text, p_user text) RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.budget_documents (clinic_id, budget_id, patient_id, storage_key, content_type, size_bytes, sha256, created_by) '
                'VALUES (%L, %L, %L, %L || ''/'' || %L || ''/budgets/'' || gen_random_uuid()::text, ''application/pdf'', 2000, repeat(''a'', 64), %L)',
                p_clinic, p_budget, p_patient, p_clinic, p_patient, p_user) $$;

CREATE FUNCTION pg_temp.inst_sql(p_id text, p_clinic text, p_budget text, p_patient text, p_number text, p_amount text, p_due text, p_user text)
  RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.installments (id, clinic_id, budget_id, patient_id, number, amount, due_date, created_by) '
                'VALUES (%L, %L, %L, %L, %L, %L, %L, %L)', p_id, p_clinic, p_budget, p_patient, p_number, p_amount, p_due, p_user) $$;

CREATE FUNCTION pg_temp.inst_status_sql(p_id text, p_status text) RETURNS text LANGUAGE sql AS $$
  SELECT format('UPDATE core.installments SET status = %L WHERE id = %L', p_status, p_id) $$;

CREATE FUNCTION pg_temp.pay_sql(p_id text, p_clinic text, p_inst text, p_kind text, p_amount text, p_paid_on text, p_reverses text, p_user text)
  RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.payments (id, clinic_id, installment_id, kind, amount, method, paid_on, reverses_payment_id, created_by) '
                'VALUES (%L, %L, %L, %L, %L, ''PIX'', %L, NULLIF(%L, '''')::uuid, %L)',
                p_id, p_clinic, p_inst, p_kind, p_amount, p_paid_on, p_reverses, p_user) $$;

-- ---------------------------------------------------------------------
-- Lote com as constraints adiáveis forçadas
-- ---------------------------------------------------------------------
CREATE FUNCTION pg_temp.expect_err_commit(p_label text, p_state text, p_stmts text[]) RETURNS void LANGUAGE plpgsql AS $$
DECLARE s text;
BEGIN
  BEGIN
    FOREACH s IN ARRAY p_stmts LOOP EXECUTE s; END LOOP;
    EXECUTE 'SET CONSTRAINTS ALL IMMEDIATE';
  EXCEPTION WHEN OTHERS THEN
    IF SQLSTATE = p_state THEN RETURN; END IF;
    RAISE EXCEPTION '%: esperado SQLSTATE %, obtido % (%)', p_label, p_state, SQLSTATE, SQLERRM;
  END;
  RAISE EXCEPTION '%: deveria falhar com SQLSTATE % (no COMMIT)', p_label, p_state;
END $$;

CREATE FUNCTION pg_temp.expect_ok_commit(p_label text, p_stmts text[]) RETURNS void LANGUAGE plpgsql AS $$
DECLARE s text;
BEGIN
  FOREACH s IN ARRAY p_stmts LOOP EXECUTE s; END LOOP;
  EXECUTE 'SET CONSTRAINTS ALL IMMEDIATE';
  EXECUTE 'SET CONSTRAINTS ALL DEFERRED';
EXCEPTION WHEN OTHERS THEN
  RAISE EXCEPTION '%: nao deveria falhar (% %)', p_label, SQLSTATE, SQLERRM;
END $$;

-- Orçamento APPROVED (2 x pt, total 300,00) com PDF, parcela única de 300,00 e pagamento parcial de 100,00.
-- Chamar COMO ADMINISTRADOR, depois de definir a sessão da clínica (:ctx_*), pois a auditoria exige app.user_id.
CREATE FUNCTION pg_temp.f3_fixture(p_clinic uuid, p_user uuid, p_patient uuid, p_proc uuid, p_budget uuid, p_inst uuid, p_pay uuid)
  RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO core.budgets (id, clinic_id, patient_id, notes, created_by) VALUES (p_budget, p_clinic, p_patient, 'orcamento fixture', p_user);
  INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position) VALUES (p_clinic, p_budget, p_proc, 2, 1);
  UPDATE core.budgets SET status = 'SENT' WHERE id = p_budget;
  INSERT INTO core.budget_documents (clinic_id, budget_id, patient_id, storage_key, content_type, size_bytes, sha256, created_by)
    VALUES (p_clinic, p_budget, p_patient, p_clinic::text || '/' || p_patient::text || '/budgets/' || gen_random_uuid()::text,
            'application/pdf', 2000, repeat('a', 64), p_user);
  UPDATE core.budgets SET status = 'APPROVED' WHERE id = p_budget;
  INSERT INTO core.installments (id, clinic_id, budget_id, patient_id, number, amount, due_date, created_by)
    VALUES (p_inst, p_clinic, p_budget, p_patient, 1, 300.00, DATE '2031-07-10', p_user);
  INSERT INTO core.payments (id, clinic_id, installment_id, kind, amount, method, paid_on, created_by)
    VALUES (p_pay, p_clinic, p_inst, 'PAYMENT', 100.00, 'PIX', DATE '2031-07-11', p_user);
END $$;
