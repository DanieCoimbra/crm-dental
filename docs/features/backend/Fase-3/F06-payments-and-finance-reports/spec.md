# Spec — F06 Pagamentos, estornos e consultas do financeiro

Complementa `docs/global/backend/Fase-3/spec.md` (seções 2.2, 3.1, 4.9, 4.10 e 6.2).

## 1. Visão técnica
- **Feature:** F06 Pagamentos e financeiro.
- **Stack:** Spring Data JPA (JPQL com projeções e agregação), Bean Validation, `Money`, `Clock`, `ClinicZones`, Spring Web MVC.
- **Abordagem:** `PaymentService`, `FinanceReportService` e `PatientFinancialService` em `crm_data.finance`; orçamentos aprovados só por `BudgetLookup`. Sem migrations: usa V027 e os índices `ix_pay_*`, `ix_inst_*` e `ix_budgets_decided`.

## 2. Dados
- **Tabela:** `core.payments` (`ix_pay_installment`, `ix_pay_clinic_paid`, `ux_pay_reverses`). Nenhuma alteração de schema.
- **`Payment`** (`TenantAwareEntity`, `@Immutable`, `Persistable<UUID>`): `installmentId`, `kind`, `amount`, `method`, `paidOn`, `reversesPaymentId`, `createdBy`, `createdAt` (`insertable = false`). Sem `@Version`, `updated_at` nem setters.
- **Repositório:** `PaymentRepository` expõe só leitura e `persist`: `findByInstallmentIdOrderByCreatedAtAscIdAsc`, `existsByReversesPaymentId`, `netPaidByInstallmentIds`, agregações de caixa. **Nenhum** método de `update`/`delete`.
- **Projeções:** `InstallmentRow(id, patientId, budgetId, status, amount, dueDate, version)` (JPQL `select new`).
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `PaymentController` | `GET /installments/{id}/payments`, `POST /installments/{id}/payments`, `POST /payments/{id}/reversal` |
| `PaymentService` | `register`, `reverse`, `list`; `paidOn`; traduz `CR010`/`CR003`/`23505` |
| `FinanceReportController` + `FinanceReportService` | `receivables`, `overdue`, `cash-flow`, `revenue` |
| `PatientFinancialController` + `PatientFinancialService` | `GET /patients/{id}/financial` |
| `BudgetLookup` (ampliada) | `approvedBetween(Instant ini, Instant fim, int limit)`, `approvedTotal(ini, fim)`, `approvedCount(ini, fim)`, `approvedOfPatient(UUID, int limit)`, `approvedTotalOfPatient(UUID)` |
| `FinanceRules` | Janela de 366 dias, `size` ≤ 100, `2000-01-01`, limite da ficha (1000) e da lista de faturamento (200) |
| `FinanceEvents` / `FinanceAuditLogger` | `PaymentRegistered(paymentId, installmentId)`, `PaymentReversed(paymentId, reversedId)`; log após commit só com ids |
| `FinanceRequests` / `FinanceResponses` | `PaymentRequest`, `PaymentResultResponse`, `ReceivableResponse`, `OverdueResponse`, `CashFlowResponse`, `RevenueResponse`, `PatientFinancialResponse` |

## 4. Lógica
**Registrar:** validar (`Money.parsePositive`, `method`) → 400; carregar a parcela por **projeção** (404); `PatientLookup.requireWritable(patientId)`; `paidOn` = informado ou hoje na clínica, maior que hoje → 422 `FUTURE_DATE`; `persist(Payment PAYMENT)` + `flush` (o banco trava a parcela, confere o saldo e atualiza o status e a `version`); reler a projeção e o líquido; evento; 201. `CR010` → 409; `CR003` → 409.

**Estornar:** carregar o pagamento (404); `kind ≠ PAYMENT` → 409; `existsByReversesPaymentId` → 409 "Este pagamento já foi estornado." (`23505` é a rede); `requireWritable` do paciente da parcela; `persist(Payment REVERSAL: mesmo valor, forma e parcela, paidOn = hoje, reversesPaymentId)` + `flush`; reler a parcela; 201.

**Relatórios (`readOnly`, JPQL):** `zone = ClinicZones.zone()`; `today = LocalDate.now(clock.withZone(zone))`; janela `to ≥ from` e ≤ 366 dias.
- `receivables`: `status = OPEN AND dueDate BETWEEN :from AND :to` ordenado por `dueDate, id`, paginado; `netPaid` por `netPaidByInstallmentIds(ids)`; `summary` por agregação.
- `overdue`: `status = OPEN AND dueDate < :today`, mais antigas primeiro; `daysOverdue = today − dueDate` em Java.
- `cashFlow`: `SUM(CASE kind WHEN PAYMENT THEN amount ELSE -amount END)` com `paidOn BETWEEN :from AND :to`, agrupado por `paidOn` e por `method`.
- `revenue`: `ini = from.atStartOfDay(zone)`, `fim = to.plusDays(1).atStartOfDay(zone)` como `Instant`; `BudgetLookup.approvedBetween/approvedTotal`.
- Pacientes por `PatientLookup.briefs`; números de orçamento por `BudgetLookup.brief` (lote).

**Ficha financeira:** `approvedOfPatient` + parcelas do paciente por `ix_inst_patient` (limite 1000) + totais (`approvedTotal`, `activeInstallmentsTotal`, `paid`, `balance`, `overdue`).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Pagamento acima do saldo / em parcela cancelada | 409 (`CR010`) |
| Dois pagamentos simultâneos no último saldo | Um 201 e um 409; sem 500 |
| `paidOn` futuro | 422 `FUTURE_DATE` |
| `amount` ≤ 0, > 2 casas ou `method` inválido | 400 |
| Estornar estorno / estorno repetido | 409 / 409 |
| Pagamento ou parcela de paciente anonimizado | 409 (`CR003`); inativo passa |
| Parcela ou pagamento de outra clínica | 404 |
| Recepção em qualquer rota | 403 antes do banco |
| Janela > 366 dias, `to < from`, `from`/`to` ausentes, `size` > 100 | 400 |
| Virada de mês no fuso da clínica | O período usa o fuso (`revenue`) e a data civil (`cash-flow`) |
| Ficha com mais de 1000 parcelas ou de 1000 orçamentos aprovados | `truncated: true` (os totais vêm de agregações e continuam inteiros) |

## 6. Segurança e desempenho
- Tudo só D (`SecurityConfig` e `@PreAuthorize` no nível da classe); `clinicId`, `createdBy`, `kind` de rota errada e `reversesPaymentId` nunca do cliente; nenhum SQL nativo; a entidade `Payment` é `@Immutable`.
- Log só com `paymentId`, `installmentId`, `userId` e contagens; **nunca valor** nem nome.
- Metas (5 mil parcelas e 5 mil pagamentos na clínica): relatórios e ficha p95 ≤ 100 ms sem `Seq Scan`; pagamento p95 ≤ 100 ms; sem N+1 (`netPaid` agregado, pacientes e orçamentos em lote).
