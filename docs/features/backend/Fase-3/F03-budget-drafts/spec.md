# Spec — F03 Orçamento em rascunho

Complementa `docs/global/backend/Fase-3/spec.md` (seções 2.2, 3.1, 3.2, 4.1, 4.2, 4.4, 4.5 e 5).

## 1. Visão técnica
- **Feature:** F03 Orçamento em rascunho.
- **Stack:** Spring Web MVC, Spring Data JPA (JPQL e Criteria/`Specification`), Bean Validation, `Money` (F01), `Clock`.
- **Abordagem:** módulo `crm_data.commercial` (`web`, `service`, `domain`, `repository`). O banco calcula número, total, preço e carimbos; o backend orquestra e relê. Sem migrations: usa V024.

## 2. Dados
- **Tabelas:** `core.budgets` (`ux_budgets_clinic_number`, `ix_budgets_patient`, `ix_budgets_status`) e `core.budget_items` (`ux_items_budget_position`). Nenhuma alteração de schema.
- **`Budget`** (`TenantAwareEntity`): `number` (`insertable = false, updatable = false`), `patientId` (`updatable = false`), `appointmentId`, `status` (`@Enumerated(STRING)`), `discountAmount`, `totalAmount` (somente leitura), `validUntil`, `notes`, `sentAt`/`decidedAt` (somente leitura), `createdBy` (`updatable = false`), `createdAt`/`updatedAt` (somente leitura), `@Version Long`.
- **`BudgetItem`** (`@Immutable`, `Persistable<UUID>`, `TenantAwareEntity`): `budgetId`, `procedureTypeId` (`updatable = false`), `description`/`unitPrice`/`lineTotal` (somente leitura), `quantity`, `discountAmount`, `position`.
- **Repositórios:** `BudgetRepository` (`JpaSpecificationExecutor`; `@Lock(PESSIMISTIC_WRITE) findForUpdateById`), `BudgetItemRepository` (`findByBudgetIdOrderByPosition`, `deleteByBudgetId`).
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `BudgetController` | `POST /budgets`, `GET /budgets`, `GET /budgets/{id}`, `PUT /budgets/{id}`, `POST /budgets/{id}/discard` |
| `PatientBudgetController` | `GET /patients/{id}/budgets` |
| `BudgetService` | Criar, editar, consultar, listar e descartar; usa `PatientLookup`, `AppointmentLookup`, `ProcedureTypeLookup` e o validador |
| `BudgetDraftValidator` | Regra pura: recebe itens, preços e desconto e devolve `List<FieldError>` com caminho |
| `BudgetTransitions` | Tabela pura ação → origens, destino, papéis; sem Spring |
| `BudgetLookup` (interface pública) | `Map<UUID, BudgetBrief> brief(Collection<UUID> ids)` (`BudgetBrief(id, number, patientId)`); métodos das F05/F06 entram depois |
| `CommercialRules` | Constantes: 50 itens, quantidade 1–999, teto, tamanhos de página |
| `CommercialEvents` / `CommercialAuditLogger` | `BudgetCreated`, `BudgetUpdated`, `BudgetDiscarded`; log após commit, só ids |
| `BudgetRequests`, `BudgetResponse`, `BudgetSummaryResponse` | DTOs (dinheiro em texto decimal; `subtotalAmount` = Σ `lineTotal`) |

## 4. Lógica
**Criar:** (1) validar (4.2 da spec global) → 400; (2) `PatientLookup.requireSchedulable` → 404/409; (3) `appointmentId` → `AppointmentLookup.requireOfPatient`; (4) `ProcedureTypeLookup.quotes(ids)`: ausente → 404, inativo → 422; (5) `BudgetDraftValidator` → 400; (6) `persist(Budget DRAFT, desconto 0)` + `flush`; `persist` dos itens (`position` 1..n) + `flush`; se desconto > 0, `setDiscountAmount` + `flush`; `reload`; (7) montar a resposta (itens relidos, paciente por `briefs`); evento; 201 com `Location`. `CR003`/`CR005` → 409; `23514` residual → 422 `AMOUNT_OUT_OF_RANGE`.

**Editar:** validar; `findForUpdateById` (404); `version` ≠ → 409; `status ≠ DRAFT` → 409 com o status atual; revalidar com preços atuais; sequência com `flush` entre as etapas: **(a)** se o desconto atual > 0, zerar; **(b)** remover todos os itens; **(c)** inserir os novos; **(d)** gravar desconto final, `validUntil`, `notes`, `appointmentId`; `reload`.

**Descartar:** carregar com trava; `version`; `BudgetTransitions` (`DISCARD`: só `DRAFT`); `status = REJECTED`; `flush`; `reload` (o banco carimba `decided_at`).

**Listar:** `Specification` com os predicados presentes (`status in`, `patientId`, `number`); `PageRequest` ordenado por `createdAt DESC, id DESC`; `size` 1–50 (padrão 20); pacientes por `briefs` em lote.

**Obter:** `findById` no tenant (404) + itens por `position` + documentos ativos (consulta própria, `deleted_at IS NULL`, `revision DESC`) + paciente resumido; nunca expõe `storage_key`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Dois `POST /budgets` simultâneos | Números distintos e sequenciais (lock consultivo do banco) |
| `PUT` em orçamento enviado, aprovado ou recusado | 409 com o status atual (`CR007` é a rede) |
| `version` defasada ou RLS afetando 0 linhas | 409 `STALE_VERSION` |
| Item com desconto maior que a linha; desconto maior que a soma; total > teto | 400 com o caminho do campo |
| Preço mudou entre leitura e gravação e estourou o teto | 422 `AMOUNT_OUT_OF_RANGE` |
| Procedimento inativo / de outra clínica | 422 / 404 |
| Paciente anonimizado / inativo na criação | 409 / 409 |
| `appointmentId` de outro paciente | 409 |
| Recepção em escrita | 403 antes do banco |
| Orçamento de outra clínica | 404 |
| Lista com `status` ou `number` inválido | 400 |

## 6. Segurança e desempenho
- Escritas só D (`SecurityConfig` e `@PreAuthorize`); leituras D e R; `clinicId`, `createdBy`, `status`, `number` e valores calculados nunca do cliente.
- Log só com `budgetId`, `patientId`, `userId`, status e contagens; nunca valor, nome ou observação.
- Criar orçamento de 20 itens p95 ≤ 250 ms; lista e obter p95 ≤ 100 ms; pacientes, itens e documentos em lote (sem N+1).
