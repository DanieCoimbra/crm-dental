# Spec — F05 Decisão do orçamento e plano de parcelas

Complementa `docs/global/backend/Fase-3/spec.md` (seções 2.2, 3.1, 4.1, 4.5, 4.8, 5.1 e 5.2).

## 1. Visão técnica
- **Feature:** F05 Decisão e plano de parcelas.
- **Stack:** Spring Data JPA (JPQL), `TransactionTemplate`, `Money` (F01), `ClinicZones`, `Clock`, Spring Web MVC.
- **Abordagem:** `BudgetDecisionService` em `commercial`; `InstallmentPlanner`, `InstallmentPlanService` e `InstallmentService` em `crm_data.finance`; `finance` só conhece `commercial` por `BudgetLookup`. Sem migrations: usa V026.

## 2. Dados
- **Tabela:** `core.installments` (`ux_inst_budget_number` parcial, `ix_inst_budget`, `ix_inst_patient`, `ix_inst_open_due`). Nenhuma alteração de schema.
- **`Installment`** (`TenantAwareEntity`): `budgetId`, `patientId`, `number`, `amount` (todos `updatable = false`); `dueDate` (editável); `status` (`@Enumerated(STRING)`, mudado só por `cancel()`); `createdBy`; `createdAt`/`updatedAt` (somente leitura); `@Version Long`.
- **Repositório:** `findActiveByBudgetId` (`status <> CANCELED`), `findAllByBudgetIdOrderByNumberAscIdAsc`, `findProjectionById`; `PaymentRepository.netPaidByInstallmentIds(ids)` (`SUM(CASE kind … END) … GROUP BY installmentId`, só leitura; a entidade `Payment` nasce na F06).
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `BudgetController` (F03, ampliado) | `POST /budgets/{id}/approve`, `/reject` |
| `BudgetDecisionService` | `approve` e `reject`; transição por `BudgetTransitions`; trava do orçamento |
| `InstallmentController` | `GET/POST/PUT /budgets/{id}/installments`, `PATCH /installments/{id}/due-date` |
| `InstallmentPlanner` (puro) | `plan(total, PlanRequest, today)` → lista `(number, amount, dueDate)` ou erros |
| `InstallmentPlanService` | `create` e `redo` do plano; `PatientLookup.requireWritable`; tradução de `CR009`/`CR011`/`23505` |
| `InstallmentService` | `changeDueDate` e `listByBudget` (com `netPaid`/`balance`) |
| `BudgetLookup` (ampliada) | `requireApproved(budgetId)` (404 / 409) e `totalOf(budgetId)` |
| `FinanceRules` / `FinanceEvents` / `FinanceAuditLogger` | Constantes (60 parcelas, `2000-01-01`); `PlanCreated(count)`, `PlanRedone(count)`, `DueDateChanged`; log após commit só com ids |
| `FinanceRequests` / `FinanceResponses` | `PlanRequest` (`AUTO`/`MANUAL`), `InstallmentPlanResponse`, `InstallmentResponse` |

## 4. Lógica
**Aprovar/recusar:** `@PreAuthorize` D; carregar com trava (404); `version` ≠ → 409; `BudgetTransitions`: `APPROVE`/`REJECT` só de `SENT` (mensagens próprias para `DRAFT`); `flush`, `reload` (o banco carimba `decided_at`); evento `StatusChanged`; 200. `CR007`/`CR008` → 409.

**`InstallmentPlanner.plan`:**
- `AUTO` sem entrada: `base = splitDown(total, count)`; vencimento `i` = `firstDueDate.plusMonths(i − 1)`.
- `AUTO` com entrada: `0 < down < total` e `count ≥ 2`; parcela 1 = `down` com vencimento `today`; parcelas `2…count` = `splitDown(total − down, count − 1)` com `firstDueDate.plusMonths(k)`, `k = 0…count − 2`.
- Qualquer parcela < 0,01 → 400. `MANUAL`: valida limites e soma; ordena por `dueDate` e ordem recebida; numera 1..n.
- Invariante: Σ == total.

**Criar plano:** trava do orçamento; `requireApproved`; `PatientLookup.requireWritable` (inativo passa); existem parcelas não canceladas → 409; `today` = hoje na clínica; `plan`; `persist` de cada `Installment(OPEN)` + `flush`; o commit (`TransactionTemplate`) dispara `CR011`; reler as parcelas e responder 201.

**Refazer plano:** trava; `APPROVED`; carregar as não canceladas (nenhuma → 409); alguma com líquido pago > 0 → 409; `cancel()` em todas as `OPEN` + **`flush`**; `plan`; `persist` + `flush`; commit confere a soma; 200.

**Vencimento:** carregar (404); `version` ≠ → 409; `status ≠ OPEN` → 409; `setDueDate`; `flush`; `reload`.

**Listar:** parcelas por `number, id` (todas); `netPaid` por consulta agregada; `balance = amount − netPaid`; `activeSum` = Σ das não canceladas.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `approve`/`reject` fora de `SENT` | 409 com mensagem própria para `DRAFT`; com o status atual nos demais |
| Gerar plano em orçamento não `APPROVED` | 409 |
| Plano já existente / sem plano para refazer | 409 / 409 |
| Soma `MANUAL` ≠ total | 400 com a diferença |
| Parcela < R$ 0,01 | 400 |
| `CR011` no commit (defeito ou corrida) | 409; nada persistido |
| Dois planos simultâneos | A trava serializa; `23505` em `ux_inst_budget_number` é a rede (409) |
| Refazer com pagamento líquido > 0 | 409 "Estorne os pagamentos antes de refazer o plano." |
| Vencimento de `PAID`/`CANCELED` | 409 (`CR009` é a rede) |
| Paciente anonimizado | 409 (`CR003`); inativo passa |
| `version` defasada ou trigger de pagamento alterou a linha | 409 `STALE_VERSION` |
| Recepção; parcela de outra clínica | 403 / 404 |

## 6. Segurança e desempenho
- Tudo só D (rotas e `@PreAuthorize` no nível da classe de `finance`); `clinicId`, `createdBy`, `status` e `number` nunca do cliente; o backend nunca grava `PAID`/`OPEN`.
- Log só com `budgetId`, `installmentId`, `userId` e contagens; nunca valor ou nome.
- Plano de 60 parcelas p95 ≤ 250 ms (60 verificações de soma no commit); listagem do plano p95 ≤ 100 ms com uma consulta agregada.
