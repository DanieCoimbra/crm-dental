# PRD — F04 Parcelas (Fase 3)

**Derivação:** PRD global da Fase 3, US-304 e US-306 (índices), decisões C1, C2 e C3, "Padrões adotados" (entrada, arredondamento, status da parcela), FR-7, FR-10, FR-16; spec global, seções 2.3 (`installments`), 4.5 (`guard_installment`, `check_installments_sum`), S8, S10 e S11.
**Dependências externas:** F02 (`budgets` `APPROVED`, `UNIQUE (id, patient_id, clinic_id)`, `total_amount`), Fase 1 (`patients`), Fase 0 (V002). Consumida por F05 (pagamentos), F06 (auditoria) e F07 (anonimização, `CR006`).

## 1. Introdução

- **Problema:** aprovado o orçamento, a clínica precisa acompanhar quanto o paciente deve e quando; parcelas que não somam o total, geradas de orçamento não aprovado ou com status inventado corrompem o contas a receber.
- **Solução:** `core.installments` com FK composta ao orçamento, geração só de orçamento `APPROVED`, soma das parcelas ativas igual ao total conferida ao `COMMIT` (constraint trigger adiável), status coerente com os pagamentos, cancelamento controlado e visão exclusiva do `DENTIST_ADMIN`.
- **Metas:**
  1. Σ parcelas não canceladas = `total_amount` do orçamento (ou zero parcelas ativas), sempre, ao fim da transação.
  2. Parcela só nasce de orçamento `APPROVED`, `OPEN`.
  3. `PAID` ⇔ líquido pago ≥ valor; ninguém grava `PAID` sem pagamento.
  4. Recepção enxerga 0 parcelas; 0 linhas de outra clínica.
  5. Parcelas vencidas (página de 50) ≤ 10 ms com 5 mil parcelas na clínica e 100 mil no banco.

## 2. User stories

### US-001: Gerar parcelas do orçamento aprovado
**Descrição:** Como dentista, quero gerar as parcelas de um orçamento aprovado, com a entrada como parcela 1.

**Critérios de aceite:**
- [ ] `core.installments` conforme a spec global (2.3): `budget_id`, `patient_id`, `number` (1 a 60), `amount` (0,01 a 999.999,99), `due_date`, `status` (`OPEN`, `PAID`, `CANCELED`; default `OPEN`), `created_by`, `created_at`, `updated_at`, `version`; `UNIQUE (id, clinic_id)`.
- [ ] FK composta `(budget_id, patient_id, clinic_id)` → `budgets (id, patient_id, clinic_id)` `RESTRICT`: o paciente é o do orçamento (`23503` se divergir).
- [ ] `INSERT` só com orçamento `APPROVED` (`DRAFT`, `SENT`, `REJECTED` → `CR009`); nasce `OPEN` (`PAID`/`CANCELED` → `CR009`); paciente anonimizado → `CR003`; paciente inativo passa.
- [ ] Número único entre as parcelas não canceladas (índice parcial); número 0 ou 61 → `23514`.

### US-002: Soma igual ao total
**Descrição:** Como dentista, quero que as parcelas sempre somem o total do orçamento.

**Critérios de aceite:**
- [ ] Constraint trigger adiável (`INITIALLY DEFERRED`, por linha, em `INSERT` e `UPDATE OF amount, status`): ao `COMMIT`, se há parcela ativa, Σ `amount` das não canceladas = `budgets.total_amount`; senão `CR011`.
- [ ] Orçamento sem parcelas ativas passa; refazer o plano (cancelar as abertas e inserir novas) na **mesma transação** passa e reaproveita os números; cancelar uma parcela sem substituir → `CR011`.

### US-003: Congelamento e cancelamento
**Descrição:** Como dentista, quero poder renegociar a data e cancelar parcela sem pagamento, sem alterar valores.

**Critérios de aceite:**
- [ ] `amount`, `number`, `budget_id`, `patient_id`, `created_by`, `created_at` imutáveis (`23514`).
- [ ] `due_date` muda só enquanto `OPEN`; em `PAID` ou `CANCELED` → `CR009`.
- [ ] `CANCELED` só de `OPEN` com líquido pago zero (inclusive depois de estorno); com saldo pago → `CR009`; sair de `CANCELED` → `CR009`.
- [ ] `status = 'PAID'` só com líquido ≥ valor e `status = 'OPEN'` só com líquido < valor (`CR009`); a atualização automática é da F05.

### US-004: Papéis, isolamento e consultas
**Descrição:** Como titular, quero que só o dentista veja e grave parcelas, e que o contas a receber seja rápido.

**Critérios de aceite:**
- [ ] RLS `ENABLE` + `FORCE`; `SELECT`, `INSERT` (`created_by = app.user_id`) e `UPDATE` só `DENTIST_ADMIN`; sem `DELETE`; recepção com 0 linhas; `SELECT TO crm_backup USING (true)`.
- [ ] Índices: `ix_inst_open_due (clinic_id, due_date, id) WHERE status = 'OPEN'`, `ix_inst_patient (clinic_id, patient_id, due_date, id)`, `ix_inst_budget (budget_id, number, id)`, `ux_inst_budget_number` parcial, `ux_inst_id_clinic`.
- [ ] `EXPLAIN` de vencidas, contas a receber de um intervalo, ficha do paciente e parcelas do orçamento sem `Seq Scan`.

## 3. Requisitos funcionais

- **FR-1:** Migration `V026__core_installments.sql` (tabela, índices, funções `guard_installment` e `check_installments_sum`, triggers, constraint trigger adiável, RLS, grants).
- **FR-2:** `crm_app` com `SELECT`, `INSERT`, `UPDATE`; sem `DELETE`; `crm_backup` com `SELECT`.
- **FR-3:** A função `core.installment_net_paid(uuid)` (líquido pago) é criada na **V026**, em PL/pgSQL, porque o guard da parcela a usa; ela lê `core.payments`, que só passa a existir na V027 (F05). Os testes da F04 que exercitam status e cancelamento rodam sobre V001–V027.
- **FR-4:** Rollback `U026`: só seguro antes de haver dados.

## 4. Fora do escopo

- Divisão do total e arredondamento (backend; a última parcela absorve os centavos).
- Juros, multa, desconto por parcela, boleto, PIX, cartão com operadora, renegociação com mudança de valor.
- Pagamento e estorno (F05).
- Auditoria (F06) e anonimização (F07).
- Cobrança, lembretes e inadimplência gravada (o atraso é derivado: `OPEN` com `due_date` anterior a "hoje", informado pelo backend).

## 5. Considerações técnicas e riscos

- A constraint trigger só falha no `COMMIT`: o `flush` do JPA não detecta `CR011`; o tratamento precisa estar no commit.
- Duas gerações simultâneas do plano do mesmo orçamento colidem no índice único parcial (`23505`), não em `CR011`.
- `installments.status` muda também por trigger de pagamento (F05); o JPA deve tratar a coluna como não atualizável e cancelar por consulta própria.
- Paciente anonimizado: nova parcela → `CR003`; anonimização com parcela `OPEN` → `CR006` (F07).
- **Dependência de ordem:** `core.installment_net_paid` nasce na V026 (corpo PL/pgSQL, não validado na criação) e a V027 cria a tabela que ela lê; até a V027, nenhuma parcela tem pagamento e a função só é chamada em mudança de status.
- **Pergunta aberta:** nenhuma.
