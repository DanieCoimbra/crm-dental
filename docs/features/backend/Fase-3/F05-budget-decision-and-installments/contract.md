# Contract — F05 Decisão do orçamento e plano de parcelas

## 1. Resumo
- **Feature:** F05-budget-decision-and-installments
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (decisão do orçamento, plano de parcelas), F06 (pagamentos e relatórios usam `Installment`) e F07 (`CR006` e anonimização). Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **Todas as rotas são só `DENTIST_ADMIN`.** Valores em texto decimal; datas `AAAA-MM-DD`.

| Método | Rota | Corpo |
|---|---|---|
| POST | `/budgets/{id}/approve` | `{ "version": long }` |
| POST | `/budgets/{id}/reject` | `{ "version": long }` |
| GET | `/budgets/{id}/installments` | — |
| POST | `/budgets/{id}/installments` | `AUTO` ou `MANUAL` (abaixo) |
| PUT | `/budgets/{id}/installments` | `AUTO` ou `MANUAL` — refaz o plano |
| PATCH | `/installments/{id}/due-date` | `{ "dueDate": date, "version": long }` |

```json
// AUTO (count inclui a entrada quando houver downPayment)
{ "mode": "AUTO", "count": 1..60, "firstDueDate": "date", "downPayment": "0.01..<total"? }
// MANUAL
{ "mode": "MANUAL", "installments": [ { "amount": "0.01..999999.99", "dueDate": "date" } ] }   // 1..60
```

`clinicId`, `status`, `number`, `netPaid`, `balance` e `createdBy` do corpo são ignorados.

## 3. Saídas

### Sucesso
- **Decisão** (200): orçamento completo da F03 com `status = APPROVED | REJECTED` e `decidedAt`.
- **Plano** (201 na criação; 200 no `GET` e no `PUT`; `no-store`):
  ```json
  { "budgetId": "uuid", "totalAmount": "800.00", "activeSum": "800.00",
    "installments": [ { "id": "uuid", "number": 1, "amount": "200.00", "dueDate": "2026-10-27",
                        "status": "OPEN", "netPaid": "0.00", "balance": "200.00", "version": 0 } ] }
  ```
  A lista traz **todas** as parcelas, inclusive `CANCELED`; `activeSum` soma só as não canceladas.
- **Vencimento** (200): `InstallmentResponse` (mesmo formato de um item acima).

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | `mode`/`count`/`firstDueDate`/`downPayment` inválidos; soma `MANUAL` ≠ total (com a diferença); parcela < R$ 0,01; data < 2000-01-01 |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | Recepção em qualquer rota |
| 404 `not-found` | Orçamento ou parcela inexistente / de outra clínica |
| 409 `conflict` | `approve`/`reject` fora de `SENT`; orçamento não `APPROVED`; plano já existente; sem plano para refazer; pagamento líquido > 0 ao refazer; vencimento de `PAID`/`CANCELED`; paciente anonimizado; `version` defasada; `CR011` no commit |

## 4. Regras e limites
- Só orçamento `SENT` é decidido; `APPROVED` e `REJECTED` são terminais. O `DRAFT` é descartado pela F03.
- Plano: 1 a 60 parcelas, soma **igual ao total**; arredondamento `DOWN` e a última absorve os centavos; a entrada é a parcela 1, vence na data da geração (data local da clínica).
- O `status` da parcela é do banco (`OPEN`/`PAID`/`CANCELED`); não existe cancelamento isolado: só refazer o plano, e só se o líquido pago for zero.
- `PATCH due-date` só em `OPEN`. Paciente inativo mantém plano e cobrança; anonimizado não gera plano.
- Parcelas e relatórios nunca são visíveis à recepção.

### Interfaces internas
```java
// crm_data.commercial.service.BudgetLookup (ampliada na F05)
void requireApproved(UUID budgetId);        // 404 | 409
BigDecimal totalOf(UUID budgetId);

// crm_data.finance.service.InstallmentPlanner (puro)
List<PlannedInstallment> plan(BigDecimal total, PlanRequest request, LocalDate today);   // PlannedInstallment(number, amount, dueDate)
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Budget.Approved` / `Budget.Rejected` | Após o commit | `{budgetId, userId}` |
| `Installments.PlanCreated` | Após criar o plano | `{budgetId, count, userId}` |
| `Installments.PlanRedone` | Após refazer | `{budgetId, count, userId}` |
| `Installments.DueDateChanged` | Após ajustar o vencimento | `{installmentId, userId}` |

## 6. Critérios de aceite (integração)
- [ ] `approve`/`reject` só em `SENT`; `DRAFT` → 409 com mensagem própria; terminais não mudam; R → 403.
- [ ] Σ das parcelas == total para totais e contagens aleatórios (AUTO com e sem entrada, MANUAL); a última absorve os centavos; 31/jan → 28/fev → 31/mar.
- [ ] `MANUAL` com soma errada → 400 com a diferença; parcela < R$ 0,01 → 400; plano duplicado → 409.
- [ ] Refazer: cancela as abertas sem pagamento e insere as novas na mesma transação; com líquido > 0 → 409; números reaproveitados.
- [ ] `CR011` forçado no commit → 409 e nada persistido (nunca 500).
- [ ] `PATCH due-date` em `PAID`/`CANCELED` → 409; `version` defasada → 409; paciente anonimizado → 409; inativo passa.
- [ ] A lista traz as canceladas e `activeSum` correto; `netPaid` por consulta agregada (sem N+1).
- [ ] Nenhum valor ou nome em log; nenhuma linha de outra clínica (filtro desligado); `ddl-auto=validate` passa com `Installment`.
