# Contract — F06 Pagamentos, estornos e consultas do financeiro

## 1. Resumo
- **Feature:** F06-payments-and-finance-reports
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (baixa de parcela, estorno, contas a receber, inadimplência, caixa e faturamento) e, na Fase 4, o dashboard. Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **Todas as rotas são só `DENTIST_ADMIN`.** Valores em texto decimal; datas `AAAA-MM-DD` locais da clínica.

| Método | Rota | Corpo / query |
|---|---|---|
| GET | `/installments/{id}/payments` | — |
| POST | `/installments/{id}/payments` | `{ "amount": "0.01..999999.99", "method": "CASH\|PIX\|DEBIT_CARD\|CREDIT_CARD\|BANK_TRANSFER\|OTHER", "paidOn": "date"? }` |
| POST | `/payments/{id}/reversal` | sem corpo |
| GET | `/finance/receivables` | `?from&to&page=0..&size=1..100` |
| GET | `/finance/overdue` | `?page&size` (`size` ≤ 100) |
| GET | `/finance/cash-flow` | `?from&to` (janela ≤ 366 dias) |
| GET | `/finance/revenue` | `?from&to` (janela ≤ 366 dias) |
| GET | `/patients/{id}/financial` | — |

`clinicId`, `kind`, `installmentId` (no corpo), `reversesPaymentId`, `createdBy` e `status` são ignorados. Não há `PUT`, `PATCH` nem `DELETE` de pagamento.

## 3. Saídas

### Sucesso
- **Pagamento / estorno** (201, `Location` do pagamento):
  ```json
  { "payment": { "id": "uuid", "kind": "PAYMENT", "amount": "200.00", "method": "PIX", "paidOn": "2026-10-27",
                 "reversesPaymentId": null, "createdBy": "uuid", "createdAt": "instant" },
    "installment": { "id": "uuid", "status": "PAID", "netPaid": "200.00", "balance": "0.00", "version": 1 } }
  ```
- **Lista de pagamentos** (200): `[payment]` em ordem cronológica.
- **Contas a receber / inadimplência** (200): `{ "content": [ { "installmentId", "number", "amount", "dueDate", "netPaid", "balance", "patient": {"id","name"}, "budget": {"id","number"}, "daysOverdue"? } ], "page", "size", "totalElements", "totalPages", "summary": { "count": 0, "balanceTotal": "0.00" } }`
- **Fluxo de caixa** (200): `{ "from", "to", "netTotal": "4300.00", "byDay": [ {"date","net"} ], "byMethod": [ {"method","net"} ] }`
- **Faturamento** (200): `{ "from", "to", "count": 0, "totalAmount": "0.00", "budgets": [ {"id","number","patient":{"id","name"},"totalAmount","decidedAt"} ] (≤ 200), "truncated": false }`
- **Ficha financeira** (200): `{ "patientId", "approvedTotal", "activeInstallmentsTotal", "paid", "balance", "overdue", "budgets": [...], "installments": [...] (≤ 1000), "truncated": false }`
- Todas com `Cache-Control: no-store`.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | `amount` ≤ 0 ou com > 2 casas; `method` inválido; janela > 366 dias, `to < from`, datas ausentes/inválidas; `page`/`size` fora da faixa |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | Recepção em qualquer rota |
| 404 `not-found` | Parcela, pagamento ou paciente inexistente / de outra clínica |
| 409 `conflict` | Pagamento acima do saldo ou em parcela cancelada; estorno de estorno ou repetido; paciente anonimizado |
| 422 `business-rule` | `reason: FUTURE_DATE` |

## 4. Regras e limites
- Pagamento parcial permitido; o líquido (Σ `PAYMENT` − Σ `REVERSAL`) nunca passa do valor da parcela nem fica negativo; o **banco** decide sob trava da parcela.
- O `status` da parcela muda por trigger (`PAID` ao quitar, `OPEN` ao estornar); o backend nunca o grava.
- Estorno é total e é uma **nova linha**; o histórico nunca se altera.
- Caixa (entradas) por `paidOn`; competência (faturamento) por `decidedAt` no fuso da clínica; atraso = `OPEN` com `dueDate` anterior a hoje na clínica (derivado).
- Sem `Idempotency-Key`: dois envios iguais geram dois lançamentos se couber no saldo.

### Interfaces internas
```java
// crm_data.commercial.service.BudgetLookup (ampliada na F06)
List<ApprovedBudget> approvedBetween(Instant ini, Instant fim, int limit);   // ApprovedBudget(id, number, patientId, totalAmount, decidedAt)
BigDecimal approvedTotal(Instant ini, Instant fim);
long approvedCount(Instant ini, Instant fim);                                          // total para saber se a lista foi truncada
List<ApprovedBudget> approvedOfPatient(UUID patientId, int limit);                    // até limit (1001 na ficha, para detectar truncamento)
BigDecimal approvedTotalOfPatient(UUID patientId);                                    // soma por agregação, nunca pela lista limitada
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Payment.Registered` | Após o commit do pagamento | `{paymentId, installmentId, userId}` |
| `Payment.Reversed` | Após o commit do estorno | `{paymentId, reversedPaymentId, installmentId, userId}` |

## 6. Critérios de aceite (integração)
- [ ] D registra (201) e estorna; R recebe 403 em todas as rotas; sem token, 401.
- [ ] 20 repetições de dois pagamentos simultâneos no último saldo: um 201 e um 409; em parcelas diferentes, ambos 201; nenhum 500.
- [ ] Parcial + restante → `PAID`; estorno de pagamento quitado → `OPEN`; estorno de estorno e estorno repetido → 409.
- [ ] `paidOn` futuro → 422; ausente = hoje na clínica; paciente inativo passa, anonimizado → 409.
- [ ] Caixa líquido considera estornos; faturamento usa o fuso da clínica na virada do mês; atraso derivado de "hoje" na clínica.
- [ ] Nenhum `UPDATE`/`DELETE` de pagamento no código; a entidade é `@Immutable`.
- [ ] Relatórios e ficha p95 ≤ 100 ms com 5 mil parcelas e 5 mil pagamentos, sem `Seq Scan`; sem N+1.
- [ ] Nenhum valor, nome ou observação em log; nenhuma linha de outra clínica (filtro desligado); `ddl-auto=validate` passa com `Payment`.
