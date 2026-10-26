# PRD — F05 Decisão do orçamento e plano de parcelas

**Derivação:** PRD global da Fase 3, US-P3-07, US-P3-08 e US-P3-09, FR-3, FR-6, FR-7 e FR-12; decisões H3 e H4; padrões "Status da parcela é do banco" e "`CR011` só aparece no commit". Spec global, seções 2.2 (`Installment`), 4.5 (aprovar e recusar), 4.8 e 5.1.
**Dependências externas:** F01 (`Money.splitDown`, tradução de `CR009`/`CR011`/`23505` e de `TransactionSystemException`), F03 (`Budget`, `BudgetTransitions`, `BudgetService`), F04 (orçamento em `SENT` com PDF), `PatientLookup.requireWritable`, `ClinicZones`, migration V026 (`installments`). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** depois que o paciente decide, o dentista precisa registrar a aprovação ou a recusa e, se aprovado, dividir o total em parcelas cuja soma **sempre** feche o valor, podendo refazer o plano sem apagar o histórico.
- **Solução:** `approve` e `reject` só a partir de `SENT` (H3); o plano nasce de um orçamento `APPROVED` em modo automático ou manual (H4), com arredondamento explícito, soma conferida antes do banco e `CR011` tratado no commit.
- **Metas:**
  1. Σ parcelas não canceladas == `totalAmount` em 100% dos planos válidos (teste de propriedade com totais e contagens aleatórios).
  2. `DRAFT → APPROVED` e `DRAFT → REJECTED` pelas rotas de decisão são recusados com 409, embora o banco os aceitasse.
  3. Uma soma errada forçada no commit retorna 409 (`CR011`) e nada é persistido; nunca 500.
  4. Cobertura ≥ 80% em `BudgetDecisionService`, `InstallmentPlanService` e `InstallmentPlanner`.

## 2. User stories

### US-001: Aprovar e recusar
**Descrição:** Como dentista, quero registrar a decisão do paciente sobre o orçamento enviado.

**Critérios de aceite:**
- [ ] `POST /api/v1/budgets/{id}/approve` e `/reject` (só D, `{version}`) valem **só para `SENT`**: `SENT → APPROVED` ou `SENT → REJECTED`; o banco carimba `decided_at`.
- [ ] `approve` em `DRAFT` → 409 "Envie o orçamento antes de aprová-lo."; `reject` em `DRAFT` → 409 "Use descartar para rascunhos."; a recusa de rascunho é o `discard` da F03.
- [ ] `APPROVED` e `REJECTED` são terminais: toda ação posterior, inclusive `PUT`, retorna 409 com o status atual; nunca volta a `DRAFT`.
- [ ] A aprovação **não** gera parcelas; orçamento aprovado sem parcelas é válido.
- [ ] `version` defasada → 409; recepção → 403; transição fora da tabela → 409 com o status atual.

### US-002: Gerar o plano de parcelas
**Descrição:** Como dentista, quero dividir o orçamento aprovado em parcelas.

**Critérios de aceite:**
- [ ] `POST /budgets/{id}/installments` (só D): orçamento `APPROVED` (senão 409 "Aprove o orçamento antes de gerar as parcelas."); modo **`AUTO`** `{count 1–60, firstDueDate, downPayment?}` ou **`MANUAL`** `{installments: [{amount, dueDate}] (1–60)}`.
- [ ] `AUTO` sem entrada: `count` parcelas, as `count − 1` primeiras com o valor dividido `RoundingMode.DOWN` e a **última absorvendo os centavos**; vencimentos `firstDueDate.plusMonths(i − 1)` calculados da primeira data (31/jan → 28/fev → 31/mar).
- [ ] `AUTO` com `downPayment` (> 0 e < total, exige `count ≥ 2`): a **parcela 1 é a entrada**, vence **hoje na clínica**; as demais dividem o restante pela mesma regra.
- [ ] `MANUAL`: cada `amount` em [0,01; 999999,99], `dueDate` ≥ 2000-01-01, **soma igual ao total** (senão 400 com a diferença); numeradas 1..n por `dueDate` e, em empate, pela ordem recebida.
- [ ] Parcela abaixo de R$ 0,01 → 400 "Parcelas pequenas demais para o total."; já existe plano ativo → 409 "O orçamento já tem plano de parcelas. Refaça o plano."
- [ ] Paciente **inativo** pode ter plano; **anonimizado** → 409; a soma é conferida no commit (`CR011` → 409). Resposta 201 com o plano (parcelas, `activeSum`, `netPaid = 0`, `balance`).

### US-003: Refazer o plano e ajustar vencimento
**Descrição:** Como dentista, quero corrigir o plano sem apagar o histórico.

**Critérios de aceite:**
- [ ] `PUT /budgets/{id}/installments` (só D) cancela as parcelas `OPEN` sem pagamento e insere o plano novo na **mesma transação** (mesmos modos e validações), com `flush` entre o cancelamento e a inserção; números 1..n reaproveitados.
- [ ] Parcela com líquido pago > 0 (inclusive `PAID`) → 409 "Estorne os pagamentos antes de refazer o plano."; sem plano → 409.
- [ ] `PATCH /installments/{id}/due-date` (só D, `{dueDate, version}`) só em `OPEN`; `PAID` ou `CANCELED` → 409.
- [ ] `GET /budgets/{id}/installments` (só D) lista todas as parcelas, **inclusive canceladas**, com `status`, `amount`, `dueDate`, `netPaid`, `balance`, `version` e `activeSum`.
- [ ] **Não existe rota de cancelamento isolado de parcela** (quebraria a soma); `PUT` e `POST` do plano serializam por trava do orçamento.
- [ ] Parcela de outra clínica → 404; recepção → 403.

## 3. Requisitos funcionais

- **FR-1:** `BudgetDecisionService` com `approve` e `reject` (`BudgetTransitions`: só `SENT`); eventos `Budget.Approved` e `Budget.Rejected`.
- **FR-2:** `InstallmentPlanner` puro (`AUTO` e `MANUAL`), usando `Money.splitDown`.
- **FR-3:** `InstallmentPlanService` com `create` e `redo` (trava do orçamento, `TransactionTemplate`, tradução de `CR009`/`CR011`/`23505`) e `InstallmentService` com ajuste de vencimento e listagem.
- **FR-4:** Entidade `Installment` (`status` editável só por `cancel()`; `@Version`); o status nunca é gravado como `PAID`/`OPEN` pelo backend.
- **FR-5:** `BudgetLookup` ganha `requireApproved(budgetId)` e `totalOf(budgetId)`.
- **FR-6:** `netPaid` e `balance` por **uma** consulta agregada em `payments` para todas as parcelas.
- **FR-7:** Eventos `Installments.PlanCreated`, `PlanRedone` e `DueDateChanged`, só ids e contagem (nunca valor).
- **FR-8:** `Phase3SchemaValidateIT` ganha `Installment`.

## 4. Fora do escopo

- Pagamentos, estornos e relatórios (F06); cancelamento isolado de parcela; juros, multa e renegociação.
- Geração automática de parcelas na aprovação (duas chamadas, por decisão).
- Cartão com operadora, boleto e PIX integrados.
- Migration ou alteração de banco.

## 5. Considerações técnicas e riscos

- O Hibernate executa `INSERT` antes de `UPDATE` no flush: sem `flush()` entre cancelar e inserir, a unicidade parcial de `(budget_id, number)` falha.
- `CR011` só aparece no commit (`TransactionSystemException`); o plano usa `TransactionTemplate` e o handler da F01.
- A entidade `Installment` é sempre carregada na própria transação antes de gravar; se um trigger de pagamento mexer na linha entre a leitura e a gravação, o `@Version` falha (409).
- Datas por `plusMonths` a partir da primeira data evitam o "deslize" do dia 31.
