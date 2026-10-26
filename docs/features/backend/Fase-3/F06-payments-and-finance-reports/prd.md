# PRD — F06 Pagamentos, estornos e consultas do financeiro

**Derivação:** PRD global da Fase 3, US-P3-10, US-P3-11 e US-P3-12, FR-8, FR-9 e FR-12; padrões "Pagamento sem pré-checagem de saldo como autoridade", "Período de relatório" e "Sem idempotência de pagamento". Spec global, seções 2.2 (`Payment`), 4.9, 4.10 e 6.2.
**Dependências externas:** F01 (`Money`, `CR010`, `23505` de estorno, `ClinicZones`), F03 (`BudgetLookup.brief`), F05 (`Installment`, `InstallmentService`, `BudgetLookup.requireApproved`), `PatientLookup.requireWritable/briefs`, migration V027 (`payments`). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** o dentista precisa registrar o que o paciente pagou em cada parcela, inclusive parcialmente, desfazer um lançamento errado **sem perder o histórico** e enxergar o que está a receber, vencido, recebido e faturado.
- **Solução:** pagamentos e estornos como linhas só de inserção (o banco mantém o status da parcela e serializa por trava), e consultas do financeiro sobre os índices do banco, com período local da clínica.
- **Metas:**
  1. Dois pagamentos simultâneos sobre o último saldo resultam em exatamente um 201 e um 409, em 20 repetições, sem 500; em parcelas diferentes, ambos passam.
  2. `receivables`, `overdue`, `cash-flow`, `revenue` e a ficha do paciente com p95 ≤ 100 ms (5 mil parcelas e 5 mil pagamentos na clínica; 100 mil de cada no banco) e **sem `Seq Scan`**.
  3. Nenhum `UPDATE` ou `DELETE` de pagamento existe no código; o líquido nunca excede o valor da parcela nem fica negativo.
  4. Cobertura ≥ 80% em `PaymentService` e `FinanceReportService`.

## 2. User stories

### US-001: Registrar pagamento
**Descrição:** Como dentista, quero registrar o que o paciente pagou em uma parcela, inclusive parcialmente.

**Critérios de aceite:**
- [ ] `POST /api/v1/installments/{id}/payments` (só D) recebe `{amount, method, paidOn?}`; `method` ∈ `CASH`, `PIX`, `DEBIT_CARD`, `CREDIT_CARD`, `BANK_TRANSFER`, `OTHER`; cria `kind = PAYMENT` e devolve 201 com `payment` e o `installment` atual (`status`, `netPaid`, `balance`, `version`).
- [ ] `paidOn` ausente = hoje no fuso da clínica; data **futura** → 422 `FUTURE_DATE`; sem campo de texto livre.
- [ ] Valor maior que o saldo ou parcela `CANCELED` → 409 (`CR010`); `amount` ≤ 0 ou com mais de 2 casas → 400.
- [ ] O último centavo faz a parcela `PAID` **por trigger**; o backend não grava o status. Pagamento parcial seguido do restante passa.
- [ ] Paciente **inativo** aceito; **anonimizado** → 409. Dois pagamentos simultâneos no último saldo: um 201 e um 409.
- [ ] `GET /installments/{id}/payments` (só D) lista pagamentos e estornos em ordem cronológica; recepção → 403; parcela de outra clínica → 404.

### US-002: Estornar pagamento
**Descrição:** Como dentista, quero desfazer um lançamento errado sem perder o histórico.

**Critérios de aceite:**
- [ ] `POST /payments/{id}/reversal` (só D, sem corpo) cria `kind = REVERSAL` com o mesmo valor, forma e parcela e `reversesPaymentId`; `paidOn` = hoje na clínica; devolve 201 com o estorno e a parcela atualizada.
- [ ] A parcela volta a `OPEN` se estava `PAID`.
- [ ] Estornar um estorno → 409; segundo estorno do mesmo pagamento → 409 "Este pagamento já foi estornado."; pagamento de outra clínica → 404.
- [ ] Não existe `PUT`, `PATCH` nem `DELETE` de pagamento; o estorno é total.

### US-003: Contas a receber e inadimplência
**Descrição:** Como dentista, quero ver o que está a receber e o que está vencido.

**Critérios de aceite:**
- [ ] `GET /finance/receivables?from&to&page&size` (só D): parcelas `OPEN` com vencimento no intervalo (datas locais), ordenadas por `dueDate, id`, com paciente resumido, `number` do orçamento, `netPaid` e `balance`; `size` ≤ 100; `summary {count, balanceTotal}`.
- [ ] `GET /finance/overdue?page&size` (só D): parcelas `OPEN` com `dueDate` anterior a **hoje na clínica**, mais antigas primeiro, com `daysOverdue`; `summary {count, balanceTotal}`. **Atraso é derivado**, nunca gravado.
- [ ] Janela inválida, `to < from` ou `size` fora da faixa → 400; recepção → 403.

### US-004: Fluxo de caixa e faturamento
**Descrição:** Como dentista, quero ver o que entrou e o que faturei no período.

**Critérios de aceite:**
- [ ] `GET /finance/cash-flow?from&to` (só D): entradas **líquidas** (Σ `PAYMENT` − Σ `REVERSAL`) por `paidOn` (regime de **caixa**): `netTotal`, `byDay`, `byMethod`; janela ≤ 366 dias. Só receitas.
- [ ] `GET /finance/revenue?from&to` (só D): soma de `totalAmount` dos orçamentos `APPROVED` por `decidedAt` (regime de **competência**), período convertido pelo fuso da clínica; `count`, `totalAmount` e lista resumida (até 200, `truncated`).
- [ ] `GET /patients/{id}/financial` (só D): orçamentos aprovados, todas as parcelas (limite 1000, `truncated`) e totais (`approvedTotal`, `activeInstallmentsTotal`, `paid`, `balance`, `overdue`); paciente inativo ou anonimizado continua consultável.
- [ ] Todas as respostas com `Cache-Control: no-store`; sem `Seq Scan`.

## 3. Requisitos funcionais

- **FR-1:** Entidade `Payment` (`@Immutable`, `Persistable`, sem `@Version`); repositório só com leitura e `persist`.
- **FR-2:** `PaymentService.register` e `reverse`, com projeção da parcela (nunca a entidade) e releitura do status após o `flush`.
- **FR-3:** `FinanceReportService` com `receivables`, `overdue`, `cashFlow` e `revenue` em JPQL, `readOnly`, sem SQL nativo.
- **FR-4:** `PatientFinancialService` e `PatientFinancialController`.
- **FR-5:** `BudgetLookup` ganha `approvedBetween(ini, fim, limite)`, `approvedTotal(ini, fim)` e `approvedOfPatient(patientId)`.
- **FR-6:** "Hoje" = `LocalDate.now(clock.withZone(ClinicZones.zone()))`; nunca `LocalDate.now()`.
- **FR-7:** Eventos `Payment.Registered` e `Payment.Reversed`, só ids (nunca valor).
- **FR-8:** `Phase3SchemaValidateIT` ganha `Payment`.

## 4. Fora do escopo

- `Idempotency-Key` em pagamento (risco aceito: dois cliques = dois lançamentos se couber; a correção é o estorno).
- Estorno parcial, crédito do paciente, troca de forma sem estorno, juros e multa.
- Fluxo de caixa com saídas, exportação CSV/PDF dos relatórios, gráficos e dashboard (Fase 4).
- Migration ou alteração de banco.

## 5. Considerações técnicas e riscos

- O trigger do banco atualiza a parcela (`status`, `version + 1`) na inserção do pagamento: a entidade `Installment` **não** pode estar carregada no mesmo contexto; usar projeção.
- Pré-checagem de saldo não é a autoridade: sob a trava da parcela o banco decide (`CR010`); o saldo mostrado pode estar defasado e o front recarrega após 409.
- Predicados com `=`, `<`, `>=` e `OR` de igualdades (lição da Fase 2: `IN` e operadores não *leakproof* podem perder o índice sob RLS).
- Competência × caixa: a mudança de mês usa o fuso da clínica; testar a virada.
