# PRD — F05 Pagamentos e estorno (Fase 3)

**Derivação:** PRD global da Fase 3, US-305 e US-308, decisões C1 a C3, "Padrões adotados" (pagamento, estorno, sem texto livre), FR-8, FR-9, FR-11; spec global, seções 2.3 (`payments`), 4.5 (`guard_payment`, `sync_installment_status`, `payments_append_only`), S10 e S16.
**Dependências externas:** F04 (`installments`, `UNIQUE (id, clinic_id)`, `guard_installment`, `installment_net_paid`), Fase 1 (`patients`), Fase 0 (V002, `users`). Consumida por F06 (auditoria) e pelo backend (registro de pagamento e relatórios de caixa).

## 1. Introdução

- **Problema:** dinheiro recebido não pode ser editado, apagado nem lançado a maior; dois lançamentos simultâneos no mesmo saldo não podem os dois passar, e o status da parcela precisa refletir o que foi pago.
- **Solução:** `core.payments` **append-only** (grant, RLS e trigger), pagamento parcial permitido, estorno como nova linha `REVERSAL`, saldo travado na parcela sob lock de linha e atualização automática do status `OPEN`↔`PAID`.
- **Metas:**
  1. 0 pagamentos com `UPDATE`/`DELETE`/`TRUNCATE`, por qualquer papel.
  2. Líquido pago nunca excede o valor da parcela nem fica negativo.
  3. Dois pagamentos simultâneos no último saldo: exatamente um passa.
  4. `INSERT` de pagamento (guard + status + auditoria) ≤ 15 ms.
  5. Soma de recebimentos do mês ≤ 10 ms com 5 mil pagamentos na clínica e 100 mil no banco.

## 2. User stories

### US-001: Registrar pagamento (parcial ou total)
**Descrição:** Como dentista, quero registrar quanto o paciente pagou de uma parcela, inclusive em partes.

**Critérios de aceite:**
- [ ] `core.payments` conforme a spec global (2.3): `installment_id`, `kind` (`PAYMENT`/`REVERSAL`), `amount` (0,01 a 999.999,99), `method` (`CASH`, `PIX`, `DEBIT_CARD`, `CREDIT_CARD`, `BANK_TRANSFER`, `OTHER`), `paid_on` (`date`), `reverses_payment_id`, `created_by`, `created_at`; sem `updated_at`, sem `version`, sem texto livre.
- [ ] FK composta `(installment_id, clinic_id)` → `installments (id, clinic_id)` `RESTRICT`.
- [ ] Líquido pago (Σ `PAYMENT` − Σ `REVERSAL`) mais o novo valor não pode exceder `installments.amount` (`CR010`); pagamento em parcela `CANCELED` → `CR010`.
- [ ] Paciente anonimizado → `CR003`; paciente inativo passa (a dívida continua).

### US-002: Status automático da parcela
**Descrição:** Como dentista, quero que a parcela fique quitada sozinha quando o pago cobre o valor.

**Critérios de aceite:**
- [ ] `AFTER INSERT`: a parcela vira `PAID` quando o líquido ≥ valor e volta a `OPEN` quando um estorno o reduz; a atualização incrementa `version` da parcela.
- [ ] A atualização passa por `guard_installment` (F04) e gera sua linha de auditoria (F06).
- [ ] Pagamento parcial mantém a parcela `OPEN`.

### US-003: Estorno
**Descrição:** Como dentista, quero estornar um lançamento errado sem perder o histórico.

**Critérios de aceite:**
- [ ] `REVERSAL` exige `reverses_payment_id` (e `PAYMENT` exige nulo): `CHECK`; FK composta `(reverses_payment_id, installment_id, clinic_id)` → `payments (id, installment_id, clinic_id)` (mesma parcela; `23503` se outra).
- [ ] O estorno repete o **valor** do pagamento original e o alvo deve ser `PAYMENT` (`CR010`); um estorno por pagamento (`UNIQUE (reverses_payment_id)`, `23505`); não se estorna um estorno (`CR010`).
- [ ] Depois do estorno de parcela quitada, a parcela volta a `OPEN` (e pode ser cancelada, F04).

### US-004: Append-only e menor privilégio
**Descrição:** Como titular, quero que o histórico de pagamentos não possa ser reescrito.

**Critérios de aceite:**
- [ ] `crm_app` só com `SELECT` e `INSERT`; sem política nem grant de `UPDATE`/`DELETE`.
- [ ] Trigger `BEFORE UPDATE OR DELETE` (por linha) e `BEFORE TRUNCATE` (comando) nega com `42501` também ao `crm_owner`.
- [ ] RLS `ENABLE` + `FORCE`: `SELECT` e `INSERT` (`created_by = app.user_id`) só `DENTIST_ADMIN`; recepção com 0 linhas; `SELECT TO crm_backup USING (true)`.

### US-005: Concorrência
**Descrição:** Como dentista, quero que dois lançamentos simultâneos não paguem a parcela a maior.

**Critérios de aceite:**
- [ ] O guard trava a parcela (`FOR NO KEY UPDATE`) antes de ler o saldo; o segundo lançamento espera, relê o saldo e falha com `CR010` se não couber.
- [ ] Pagamentos em parcelas diferentes não se bloqueiam.

### US-006: Consultas de caixa
**Descrição:** Como dentista, quero ver rápido o que entrou no mês.

**Critérios de aceite:**
- [ ] Índices `ix_pay_clinic_paid (clinic_id, paid_on, id)`, `ix_pay_installment (clinic_id, installment_id)`, `ux_pay_reverses`, `ux_pay_id_inst_clinic`.
- [ ] `EXPLAIN` da soma de recebimentos por período e do líquido de uma parcela sem `Seq Scan`.

## 3. Requisitos funcionais

- **FR-1:** Migration `V027__core_payments.sql` (tabela, índices, funções `guard_payment`, `sync_installment_status`, `payments_append_only`, triggers, RLS, grants).
- **FR-2:** `crm_app` com `SELECT`, `INSERT`; sem `UPDATE`/`DELETE`/`TRUNCATE`; `crm_backup` com `SELECT`.
- **FR-3:** Rollback `U027`: só seguro antes de haver dados.

## 4. Fora do escopo

- Estorno parcial, crédito do paciente (pagamento maior que o saldo), troca de forma de pagamento sem estorno.
- Integração com operadoras, PIX, boleto e conciliação bancária.
- Texto livre em pagamento; recibo e nota fiscal.
- Cálculo de "hoje" e do fuso (backend informa `paid_on`).
- Saídas de caixa e contas a pagar.

## 5. Considerações técnicas e riscos

- **Restauração:** `payments` tem trigger de `TRUNCATE`; a carga do `pg_restore` usa `COPY` (não dispara `UPDATE`/`DELETE`), e o `restore_check` só trunca `holidays`. Validar no teste de backup.
- A atualização de status da parcela roda com o papel do chamador (`DENTIST_ADMIN`); se a política de `UPDATE` de `installments` mudar, o pagamento quebra (teste dedicado).
- `paid_on` é do backend; o banco só impede datas anteriores a 2000.
- **Pergunta aberta:** nenhuma.
