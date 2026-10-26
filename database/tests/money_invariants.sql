-- =====================================================================
-- tests/money_invariants.sql | Fase 3 / F02, F04 e F05 (teste 109, de propriedade)
-- Sequência ALEATÓRIA (semente fixa) de 1.600 operações válidas e inválidas como crm_app (dentista) sobre orçamentos, itens, descontos,
-- envio, aprovação, recusa, geração e refação de parcelas, cancelamento, pagamento, estorno e tentativas de adulteração. A cada 50
-- operações e ao fim confere as invariantes do dinheiro:
--   (a) total_amount = Σ line_total dos itens - desconto do orçamento, em todo orçamento (os itens congelam junto com o total);
--   (b) onde há parcela ativa, Σ parcelas não canceladas = total_amount;
--   (c) nenhuma parcela com líquido pago > valor ou < 0;
--   (d) status PAID <=> líquido >= valor; CANCELED => líquido 0;
--   (e) orçamento fora de DRAFT: contagem e soma dos itens iguais às do instante em que saiu de DRAFT.
-- Operação inválida deve FALHAR com um código esperado e NÃO alterar nada (as invariantes continuam valendo). Se algo falhar, a mensagem
-- traz a semente e a iteração. Constraints adiáveis são forçadas dentro de cada operação (SET CONSTRAINTS ALL IMMEDIATE).
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase3_seed.sql

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

CREATE TEMP TABLE prop_snap (budget_id uuid PRIMARY KEY, n integer NOT NULL, s numeric NOT NULL);
CREATE TEMP TABLE prop_errs (state text PRIMARY KEY, n integer NOT NULL DEFAULT 0);
CREATE TEMP TABLE prop_ops (op integer PRIMARY KEY, ok integer NOT NULL DEFAULT 0, fail integer NOT NULL DEFAULT 0);
INSERT INTO prop_ops (op) SELECT g FROM generate_series(0, 15) g;

-- Registra o líquido e os itens de um orçamento que acabou de sair de DRAFT
CREATE FUNCTION pg_temp.prop_snapshot(p_budget uuid) RETURNS void LANGUAGE sql AS $$
  INSERT INTO prop_snap (budget_id, n, s)
  SELECT p_budget, count(*), COALESCE(sum(line_total), 0) FROM core.budget_items WHERE budget_id = p_budget
  ON CONFLICT (budget_id) DO NOTHING $$;

-- Escolhe uma linha: em 85% das vezes entre as que satisfazem p_where (candidatas VÁLIDAS para a operação), nas demais entre todas
-- (para exercitar as recusas). Sem nenhuma candidata válida, cai em qualquer linha.
CREATE FUNCTION pg_temp.prop_pick(p_table text, p_where text) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE v uuid;
BEGIN
  IF random() < 0.85 THEN
    EXECUTE format('SELECT id FROM %s WHERE %s ORDER BY random() LIMIT 1', p_table, p_where) INTO v;
  END IF;
  IF v IS NULL THEN
    EXECUTE format('SELECT id FROM %s ORDER BY random() LIMIT 1', p_table) INTO v;
  END IF;
  RETURN v;
END $$;
-- Uma operação aleatória. Levanta exceção (CRnnn, 23xxx, 42501) quando o banco recusa; devolve normalmente quando aceita.
CREATE FUNCTION pg_temp.prop_op(p_op integer) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  c        uuid := core.current_clinic_id();
  u        uuid := core.current_user_id();
  v_b      uuid; v_i uuid; v_p uuid; v_item uuid;
  v_amount numeric; v_net numeric; v_total numeric; v_parts integer; v_k integer; v_each numeric; v_due date;
  v_pat    uuid := (ARRAY['f2000000-0000-4000-8000-0000000000a1', 'f2000000-0000-4000-8000-0000000000a2'])[1 + floor(random() * 2)::int]::uuid;
  v_proc   uuid := (ARRAY['f4000000-0000-4000-8000-0000000000c1', 'f4000000-0000-4000-8000-0000000000c2'])[1 + floor(random() * 2)::int]::uuid;
  v_status text;
BEGIN
  CASE p_op
  WHEN 0 THEN                                   -- novo orçamento
    INSERT INTO core.budgets (clinic_id, patient_id, created_by) VALUES (c, v_pat, u);
  WHEN 1 THEN                                   -- novo item em orçamento qualquer (falha fora de DRAFT)
    v_b := pg_temp.prop_pick('core.budgets', $w$status = 'DRAFT'$w$);
    IF v_b IS NULL THEN RETURN; END IF;
    INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position, discount_amount)
    VALUES (c, v_b, v_proc, 1 + floor(random() * 3)::int,
            COALESCE((SELECT max(position) FROM core.budget_items WHERE budget_id = v_b), 0) + 1, floor(random() * 80));
  WHEN 2 THEN                                   -- altera a quantidade de um item
    v_item := pg_temp.prop_pick('core.budget_items', $w$EXISTS (SELECT 1 FROM core.budgets b WHERE b.id = core.budget_items.budget_id AND b.status = 'DRAFT')$w$);
    IF v_item IS NULL THEN RETURN; END IF;
    UPDATE core.budget_items SET quantity = 1 + floor(random() * 4)::int WHERE id = v_item;
  WHEN 3 THEN                                   -- remove um item
    v_item := pg_temp.prop_pick('core.budget_items', $w$EXISTS (SELECT 1 FROM core.budgets b WHERE b.id = core.budget_items.budget_id AND b.status = 'DRAFT')$w$);
    IF v_item IS NULL THEN RETURN; END IF;
    DELETE FROM core.budget_items WHERE id = v_item;
  WHEN 4 THEN                                   -- desconto do orçamento
    v_b := pg_temp.prop_pick('core.budgets', $w$status = 'DRAFT' AND EXISTS (SELECT 1 FROM core.budget_items i WHERE i.budget_id = core.budgets.id)$w$);
    IF v_b IS NULL THEN RETURN; END IF;
    UPDATE core.budgets SET discount_amount = floor(random() * 300) WHERE id = v_b;
  WHEN 5 THEN                                   -- enviar
    v_b := pg_temp.prop_pick('core.budgets', $w$status = 'DRAFT' AND EXISTS (SELECT 1 FROM core.budget_items i WHERE i.budget_id = core.budgets.id)$w$);
    IF v_b IS NULL THEN RETURN; END IF;
    UPDATE core.budgets SET status = 'SENT' WHERE id = v_b;
    PERFORM pg_temp.prop_snapshot(v_b);
  WHEN 6 THEN                                   -- aprovar
    v_b := pg_temp.prop_pick('core.budgets', $w$(status = 'SENT' OR status = 'DRAFT') AND EXISTS (SELECT 1 FROM core.budget_items i WHERE i.budget_id = core.budgets.id)$w$);
    IF v_b IS NULL THEN RETURN; END IF;
    UPDATE core.budgets SET status = 'APPROVED' WHERE id = v_b;
    PERFORM pg_temp.prop_snapshot(v_b);
  WHEN 7 THEN                                   -- recusar
    v_b := pg_temp.prop_pick('core.budgets', $w$status = 'DRAFT'$w$);
    IF v_b IS NULL THEN RETURN; END IF;
    UPDATE core.budgets SET status = 'REJECTED' WHERE id = v_b;
    PERFORM pg_temp.prop_snapshot(v_b);
  WHEN 8 THEN                                   -- gera o plano de um orçamento aprovado (soma certa, ou errada de propósito)
    v_b := pg_temp.prop_pick('core.budgets', $w$status = 'APPROVED' AND NOT EXISTS (SELECT 1 FROM core.installments i WHERE i.budget_id = core.budgets.id AND i.status <> 'CANCELED')$w$);
    SELECT b.total_amount, b.patient_id INTO v_total, v_pat FROM core.budgets b WHERE b.id = v_b;
    IF v_b IS NULL THEN RETURN; END IF;
    v_parts := 1 + floor(random() * 4)::int;
    v_each := round(v_total / v_parts, 2);
    FOR v_k IN 1 .. v_parts LOOP
      INSERT INTO core.installments (clinic_id, budget_id, patient_id, number, amount, due_date, created_by)
      VALUES (c, v_b, v_pat, v_k,
              CASE WHEN v_k = v_parts THEN v_total - v_each * (v_parts - 1) + CASE WHEN random() < 0.15 THEN 1 ELSE 0 END ELSE v_each END,
              DATE '2031-10-01' + v_k * 30, u);
    END LOOP;
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
  WHEN 9 THEN                                   -- refaz o plano: cancela as parcelas ativas e gera de novo
    v_b := pg_temp.prop_pick('core.budgets', $w$status = 'APPROVED' AND EXISTS (SELECT 1 FROM core.installments i WHERE i.budget_id = core.budgets.id AND i.status <> 'CANCELED' AND NOT EXISTS (SELECT 1 FROM core.payments p WHERE p.installment_id = i.id))$w$);
    SELECT b.total_amount, b.patient_id INTO v_total, v_pat FROM core.budgets b WHERE b.id = v_b;
    IF v_b IS NULL THEN RETURN; END IF;
    UPDATE core.installments SET status = 'CANCELED' WHERE budget_id = v_b AND status <> 'CANCELED';
    v_parts := 1 + floor(random() * 3)::int;
    v_each := round(v_total / v_parts, 2);
    FOR v_k IN 1 .. v_parts LOOP
      INSERT INTO core.installments (clinic_id, budget_id, patient_id, number, amount, due_date, created_by)
      VALUES (c, v_b, v_pat, v_k, CASE WHEN v_k = v_parts THEN v_total - v_each * (v_parts - 1) ELSE v_each END, DATE '2032-01-01' + v_k * 30, u);
    END LOOP;
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
  WHEN 10 THEN                                  -- paga uma parcela (às vezes a mais)
    v_i := pg_temp.prop_pick('core.installments', $w$status = 'OPEN'$w$);
    SELECT amount INTO v_amount FROM core.installments WHERE id = v_i;
    IF v_i IS NULL THEN RETURN; END IF;
    v_net := core.installment_net_paid(v_i);
    INSERT INTO core.payments (clinic_id, installment_id, kind, amount, method, paid_on, created_by)
    VALUES (c, v_i, 'PAYMENT',
            CASE WHEN random() < 0.15 THEN v_amount + 1
                 WHEN random() < 0.4 THEN greatest(0.01, v_amount - v_net)
                 ELSE greatest(0.01, round((random() * (v_amount - v_net))::numeric, 2)) END,
            'PIX', DATE '2031-12-01', u);
  WHEN 11 THEN                                  -- estorna um pagamento (às vezes com valor errado)
    v_p := pg_temp.prop_pick('core.payments', $w$kind = 'PAYMENT' AND NOT EXISTS (SELECT 1 FROM core.payments r WHERE r.reverses_payment_id = core.payments.id)$w$);
    SELECT installment_id, amount INTO v_i, v_amount FROM core.payments WHERE id = v_p;
    IF v_p IS NULL THEN RETURN; END IF;
    INSERT INTO core.payments (clinic_id, installment_id, kind, amount, method, paid_on, reverses_payment_id, created_by)
    VALUES (c, v_i, 'REVERSAL', CASE WHEN random() < 0.15 THEN v_amount + 1 ELSE v_amount END, 'PIX', DATE '2031-12-02', v_p, u);
  WHEN 12 THEN                                  -- cancela uma parcela qualquer
    v_i := pg_temp.prop_pick('core.installments', $w$status = 'OPEN' AND NOT EXISTS (SELECT 1 FROM core.payments p WHERE p.installment_id = core.installments.id)$w$);
    IF v_i IS NULL THEN RETURN; END IF;
    UPDATE core.installments SET status = 'CANCELED' WHERE id = v_i;
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
  WHEN 13 THEN                                  -- grava o status da parcela à mão (PAID/OPEN, coerente ou não)
    v_i := pg_temp.prop_pick('core.installments', $w$true$w$);
    IF v_i IS NULL THEN RETURN; END IF;
    v_status := (ARRAY['PAID', 'OPEN'])[1 + floor(random() * 2)::int];
    UPDATE core.installments SET status = v_status WHERE id = v_i;
  WHEN 14 THEN                                  -- tentativa de adulterar pagamento: NUNCA pode passar
    BEGIN
      UPDATE core.payments SET amount = amount + 1;
      RAISE EXCEPTION 'ADULTERACAO PASSOU: UPDATE em payments' USING ERRCODE = 'XX001';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
    BEGIN
      DELETE FROM core.payments;
      RAISE EXCEPTION 'ADULTERACAO PASSOU: DELETE em payments' USING ERRCODE = 'XX001';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
    IF EXISTS (SELECT 1 FROM core.installments) THEN
      BEGIN
        UPDATE core.installments SET amount = amount + 1;
        RAISE EXCEPTION 'ADULTERACAO PASSOU: UPDATE do valor da parcela' USING ERRCODE = 'XX001';
      EXCEPTION WHEN check_violation THEN NULL;
      END;
    END IF;
  WHEN 15 THEN                                  -- tentativa de forjar o total do orçamento / carimbo: o banco ignora o valor
    v_b := pg_temp.prop_pick('core.budgets', $w$true$w$);
    IF v_b IS NULL THEN RETURN; END IF;
    UPDATE core.budgets SET total_amount = 1, sent_at = now() WHERE id = v_b;
  END CASE;
END $$;

CREATE FUNCTION pg_temp.prop_check(p_seed text, p_iter integer) RETURNS void LANGUAGE plpgsql AS $$
DECLARE n bigint; r record;
BEGIN
  SELECT count(*) INTO n FROM core.budgets b
   WHERE b.total_amount <> COALESCE((SELECT sum(i.line_total) FROM core.budget_items i WHERE i.budget_id = b.id), 0) - b.discount_amount;
  IF n > 0 THEN RAISE EXCEPTION 'T109 (a) % orcamento(s) com total divergente [semente %, iteracao %]', n, p_seed, p_iter; END IF;

  SELECT count(*) INTO n FROM core.budgets b
   WHERE EXISTS (SELECT 1 FROM core.installments i WHERE i.budget_id = b.id AND i.status <> 'CANCELED')
     AND (SELECT sum(i.amount) FROM core.installments i WHERE i.budget_id = b.id AND i.status <> 'CANCELED') <> b.total_amount;
  IF n > 0 THEN RAISE EXCEPTION 'T109 (b) % orcamento(s) com parcelas que nao somam o total [semente %, iteracao %]', n, p_seed, p_iter; END IF;

  SELECT count(*) INTO n FROM core.installments i
   WHERE core.installment_net_paid(i.id) > i.amount OR core.installment_net_paid(i.id) < 0;
  IF n > 0 THEN RAISE EXCEPTION 'T109 (c) % parcela(s) com liquido fora de [0, valor] [semente %, iteracao %]', n, p_seed, p_iter; END IF;

  SELECT count(*) INTO n FROM core.installments i
   WHERE (i.status <> 'CANCELED' AND ((i.status = 'PAID') <> (core.installment_net_paid(i.id) >= i.amount)))
      OR (i.status = 'CANCELED' AND core.installment_net_paid(i.id) <> 0);
  IF n > 0 THEN RAISE EXCEPTION 'T109 (d) % parcela(s) com status incoerente com o liquido [semente %, iteracao %]', n, p_seed, p_iter; END IF;

  FOR r IN SELECT b.id, s.n AS sn, s.s AS ss,
                  (SELECT count(*) FROM core.budget_items i WHERE i.budget_id = b.id) AS n,
                  COALESCE((SELECT sum(i.line_total) FROM core.budget_items i WHERE i.budget_id = b.id), 0) AS s
             FROM core.budgets b JOIN prop_snap s ON s.budget_id = b.id WHERE b.status <> 'DRAFT' LOOP
    IF r.n <> r.sn OR r.s <> r.ss THEN
      RAISE EXCEPTION 'T109 (e) itens do orcamento % mudaram depois de sair de DRAFT [semente %, iteracao %]', r.id, p_seed, p_iter;
    END IF;
  END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- Execução
-- ---------------------------------------------------------------------
DO $$
DECLARE
  i integer; k_op integer; st text;
  seed constant text := '0.42';
  allowed constant text[] := ARRAY['CR007', 'CR008', 'CR009', 'CR010', 'CR011', '23514', '23505', '23503'];
BEGIN
  PERFORM setseed(seed::double precision);
  FOR i IN 1 .. 1600 LOOP
    k_op := (ARRAY[0,0,1,1,1,1,1,2,3,4,4,5,5,5,6,6,6,7,8,8,8,8,9,9,10,10,10,10,10,11,11,12,13,13,14,15])[1 + floor(random() * 36)::int];
    BEGIN
      PERFORM pg_temp.prop_op(k_op);
      UPDATE prop_ops SET ok = ok + 1 WHERE prop_ops.op = k_op;
    EXCEPTION WHEN OTHERS THEN
      st := SQLSTATE;
      IF NOT (st = ANY (allowed)) THEN
        RAISE EXCEPTION 'T109 operacao % falhou com codigo inesperado % (%) [semente %, iteracao %]', k_op, st, SQLERRM, seed, i;
      END IF;
      UPDATE prop_ops SET fail = fail + 1 WHERE prop_ops.op = k_op;
      INSERT INTO prop_errs (state, n) VALUES (st, 1) ON CONFLICT (state) DO UPDATE SET n = prop_errs.n + 1;
    END;
    IF i % 50 = 0 THEN PERFORM pg_temp.prop_check(seed, i); END IF;
  END LOOP;
  PERFORM pg_temp.prop_check(seed, 1600);
END $$;

-- A sequência exercitou de fato o sistema (senão o teste não prova nada): mínimos de operações aceitas e de cada recusa
DO $$
DECLARE r record; v bigint; s text;
BEGIN
  FOR r IN SELECT * FROM prop_ops ORDER BY op LOOP RAISE NOTICE 'T109 op % : % aceitas, % recusadas', r.op, r.ok, r.fail; END LOOP;
  FOR r IN SELECT * FROM prop_errs ORDER BY state LOOP RAISE NOTICE 'T109 recusa % : %', r.state, r.n; END LOOP;
  SELECT count(*) INTO v FROM core.budgets;                                  IF v < 40  THEN RAISE EXCEPTION 'T109: so % orcamentos criados', v; END IF;
  SELECT count(*) INTO v FROM core.budget_items;                             IF v < 30  THEN RAISE EXCEPTION 'T109: so % itens', v; END IF;
  SELECT count(*) INTO v FROM core.budgets WHERE status = 'APPROVED';        IF v < 8   THEN RAISE EXCEPTION 'T109: so % orcamentos aprovados', v; END IF;
  SELECT count(*) INTO v FROM core.installments;                             IF v < 15  THEN RAISE EXCEPTION 'T109: so % parcelas', v; END IF;
  SELECT count(*) INTO v FROM core.payments WHERE kind = 'PAYMENT';          IF v < 15  THEN RAISE EXCEPTION 'T109: so % pagamentos', v; END IF;
  SELECT count(*) INTO v FROM core.payments WHERE kind = 'REVERSAL';         IF v < 2   THEN RAISE EXCEPTION 'T109: so % estornos', v; END IF;
  SELECT count(*) INTO v FROM core.installments WHERE status = 'PAID';       IF v < 3   THEN RAISE EXCEPTION 'T109: so % parcelas quitadas', v; END IF;
  FOREACH s IN ARRAY ARRAY['CR007', 'CR008', 'CR009', 'CR010', 'CR011'] LOOP
    IF NOT EXISTS (SELECT 1 FROM prop_errs WHERE state = s AND n > 0) THEN RAISE EXCEPTION 'T109: a sequencia nunca provocou %', s; END IF;
  END LOOP;
END $$;

RESET SESSION AUTHORIZATION;
ROLLBACK;
\o
SELECT 'money_invariants: OK' AS resultado;
