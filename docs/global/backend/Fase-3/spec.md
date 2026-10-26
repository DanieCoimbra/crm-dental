# Spec Técnica Global (Backend) — Fase 3: Comercial e Financeiro

**Base:** `docs/global/backend/Fase-3/prd.md` (aprovado em 2026-10-26) e a base entregue nas Fases 0, 1 e 2 (`docs/global/backend/Fase-{0,1,2}/{prd,spec}.md`; código em `back-java/`).
**Contrato do banco:** `docs/global/banco/Fase-3/spec.md` (seções 2.3, 4, 6 e 7) e as migrations `database/migrations/V022…V029`, **já aplicadas no Supabase real** (2026-10-26).
**Escopo:** módulos novos `catalog`, `commercial` e `finance`, e as mudanças em `scheduling`, `patient`, `record`, `clinic` e `shared`.
**Regra:** sem código de aplicação. Há apenas contratos, assinaturas, JSON e configuração ilustrativos; as classes são do `dev-engineer-java`.

> **Fronteira com o banco:** o backend não cria nem altera objetos de banco (`ddl-auto=validate`). Os nomes abaixo são os **definitivos** das migrations V022–V029. O banco já impõe o dinheiro (total, congelamento, soma das parcelas, pagamento append-only, status da parcela); o backend orquestra, valida **antes** do banco para dar erro útil e traduz o que o banco recusar.

---

## 1. Visão técnica

- **Feature:** Comercial e Financeiro (tipos de procedimento, orçamento com PDF no Storage, plano de parcelas, pagamentos e estornos, consultas do financeiro) e a integração com agenda, anonimização e auditoria.
- **Stack:** Java 21 · Spring Boot 4.1.1 (Spring Framework 7, Hibernate 7, Jackson 3 em `tools.jackson`) · Spring Data JPA · Spring Security stateless (JWT da Fase 0) · Testcontainers. **Uma dependência nova no `pom.xml`: OpenPDF** (`com.github.librepdf:openpdf`, série 2.x, pacote `com.lowagie.text`; versão exata fixada no primeiro build, ponto 7 da seção 9). Dinheiro com `BigDecimal`, datas com `java.time` e o `Clock` injetável.
- **Abordagem:** a mesma Clean Architecture por módulo (`web → service → repository`, `domain` sem Spring Web); o controller só traduz HTTP, e regra, papel e transação ficam no `service`. Isolamento em duas camadas (filtro Hibernate + RLS) e papel reforçado pelo banco. **O banco é a autoridade do dinheiro:** o backend nunca grava `total_amount`, `number`, carimbos, preço do item nem `status` de parcela; relê a linha depois de cada gravação.
- **Duas peculiaridades que orientam o desenho:**
  1. **`Storage` dentro da transação do envio (H1).** Rompe a regra "o Storage nunca é chamado dentro de uma transação" do `PatientFileService` (o pool tem 10 conexões). A contenção é: semáforo de envios simultâneos, timeout curto, limite por usuário e teto de tempo da transação (seção 4.7).
  2. **`CR011` só existe no `COMMIT`.** Hoje o `GlobalExceptionHandler` **não trata `TransactionSystemException`** (a exceção que o commit lança); sem tratar, a soma errada das parcelas viraria 500 (seção 5.1).

### 1.1 Layout de diretórios (acréscimos e alterações)

```
back-java/
├─ pom.xml                                   (ALTERADO: + openpdf)
└─ src/main/java/crm_data/
   ├─ shared/
   │  ├─ money/        Money (NOVO: parse/format/divisão em centavos, teto R$ 999.999,99; sem Spring)
   │  ├─ storage/      StorageKeys (ALTERADO: aceita <clinic>/<patient>/<uuid> e <clinic>/<patient>/budgets/<uuid>; of/ofBudget),
   │  │                FileStorage (ALTERADO: Javadoc do contrato de uso em transação), SupabaseFileStorage/LocalFileStorage (sem mudança de lógica)
   │  ├─ config/       StorageConfig (ALTERADO: + bean `documentStorage` com timeout próprio), CrmProperties (+ Documents),
   │  │                SecurityConfig (ALTERADO: rotas da seção 3.2)
   │  ├─ error/        GlobalExceptionHandler (ALTERADO: CR006–CR011, 23505 novas, TransactionSystemException, tabela 5.1),
   │  │                ConflictMessages (+ mensagens)
   │  └─ web/          ClinicalCacheHeaders (ALTERADO: no-store nas rotas novas)
   ├─ clinic/service/  ClinicIdentity (NOVO: interface pública, nome/endereço/telefone da clínica do token para o PDF)
   ├─ patient/
   │  ├─ service/      AnonymizationContributor (NOVO: SPI), PatientAnonymizationService (ALTERADO: CR006, chaves e contagem dos contribuintes)
   │  └─ web/          AnonymizationResponse (ALTERADO: + openBudgets)
   ├─ record/service/  AuditQueryService (TABLES + budgets, installments, payments, budget_documents)
   ├─ scheduling/
   │  ├─ domain/       Appointment (ALTERADO: mapeia procedureTypeId)
   │  ├─ service/      AppointmentService (ALTERADO: procedimento), SchedulingRules (+ PROCEDURE_INACTIVE)
   │  └─ web/          AppointmentRequests, AppointmentResponse (ALTERADOS: procedureTypeId / procedure)
   ├─ catalog/                                          (NOVO)
   │  ├─ web/          ProcedureTypeController, ProcedureTypeRequests, ProcedureTypeResponse
   │  ├─ service/      ProcedureTypeService, ProcedureTypeLookup (interface pública) + ProcedureTypeLookupService,
   │  │                ProcedureBrief, ProcedureQuote, CatalogEvents, CatalogAuditLogger
   │  ├─ domain/       ProcedureType
   │  └─ repository/   ProcedureTypeRepository
   ├─ commercial/                                       (NOVO)
   │  ├─ web/          BudgetController, BudgetDocumentController, PatientBudgetController, BudgetRequests,
   │  │                BudgetResponse, BudgetSummaryResponse, DocumentResponse, DocumentRateLimitInterceptor, CommercialWebConfig
   │  ├─ service/      BudgetService, BudgetDecisionService, BudgetDocumentService, BudgetDraftValidator, BudgetTransitions,
   │  │                BudgetPdfRenderer, BudgetPdfModel, DocumentSlots, DocumentRateLimiter, BudgetLookup (interface pública) +
   │  │                BudgetLookupService, BudgetAnonymizationContributor, CommercialRules, CommercialEvents, CommercialAuditLogger
   │  ├─ domain/       Budget, BudgetStatus, BudgetItem, BudgetDocument
   │  └─ repository/   BudgetRepository (JpaSpecificationExecutor), BudgetItemRepository, BudgetDocumentRepository
   └─ finance/                                          (NOVO)
      ├─ web/          InstallmentController, PaymentController, FinanceReportController, PatientFinancialController,
      │                FinanceRequests, FinanceResponses
      ├─ service/      InstallmentPlanner (puro), InstallmentPlanService, InstallmentService, PaymentService,
      │                FinanceReportService, PatientFinancialService, FinanceRules, FinanceEvents, FinanceAuditLogger
      ├─ domain/       Installment, InstallmentStatus, Payment, PaymentKind, PaymentMethod
      └─ repository/   InstallmentRepository, PaymentRepository
```

**Direção de dependência (sem ciclo):**
`finance → commercial (BudgetLookup) → catalog (ProcedureTypeLookup), patient (PatientLookup), scheduling (AppointmentLookup), clinic (ClinicZones, ClinicIdentity)`;
`scheduling → catalog (ProcedureTypeLookup)`; `commercial → patient (AnonymizationContributor é implementado em commercial, definido em patient)`.
`patient` **não** conhece `commercial` nem `finance`; `catalog` só depende de `shared`. Nenhum módulo usa o repositório de outro. `patient → record` (PatientFileService na anonimização) e `record → patient` (PatientLookup) **já existem** desde a Fase 1 e permanecem; o teste de arquitetura da Fase 3 não os altera.

---

## 2. Modelo de dados e estado

### 2.1 Mudanças de banco

**Nenhuma.** O backend consome V001–V029. Se o mapeamento exigir algo que o banco não tem (seção 9), a pendência vai para a função `banco`.

### 2.2 Mapeamento das entidades (`ddl-auto=validate`)

As entidades de negócio estendem `TenantAwareEntity`, com `@Getter`, `@NoArgsConstructor(access = PROTECTED)` e **setters só nos campos editáveis**. Proibidos `@Data`, `@ToString` e `@EqualsAndHashCode`. **Sem `@ManyToOne`/`@OneToMany`**: relações são `UUID` (o módulo vizinho resolve por interface). Colunas preenchidas por trigger do banco ou por default: `insertable = false, updatable = false`, e o service chama `flush()` + `EntityManager.refresh(entity)` depois da gravação (helper `TriggerColumns.reload`, seção 4.1). Sem `@Generated` novo (as entidades da Fase 2 que o usam, como `Appointment`, não mudam).

**`isNew` e `persist`.** Entidade com `@Version` e `id` atribuído é nova quando `version == null` (padrão das Fases 1 e 2). **Entidades sem `@Version`** (`BudgetItem`, `BudgetDocument`, `Payment`) implementam `Persistable<UUID>` com um campo transitório `isNew = true` no construtor de criação e `false` em `@PostLoad`/`@PostPersist`; sem isso o `save` faria `merge` com `SELECT` prévio. `Payment`, `BudgetItem` e `BudgetDocument` são `@Immutable`.

| Entidade (`core.<tabela>`) | Atributos e restrições de mapeamento |
|---|---|
| `ProcedureType` (`procedure_types`) | `id UUID`; `name` (≤ 80, editável); `basePrice BigDecimal` (`precision 12, scale 2`, editável); `defaultDurationMinutes Short` (editável); `color String` (7, editável); `active boolean` (editável); `createdBy` (`updatable = false`); `createdAt`/`updatedAt` (`insertable = false, updatable = false`); `@Version Long` |
| `Budget` (`budgets`) | `id`; `number Integer` (`insertable = false, updatable = false`); `patientId` (`updatable = false`); `appointmentId UUID` (editável); `status BudgetStatus` (`@Enumerated(STRING)`, `DRAFT`/`SENT`/`APPROVED`/`REJECTED`, editável só pelos métodos de transição); `discountAmount BigDecimal` (editável; `0` na criação, S7); `totalAmount` (`insertable = false, updatable = false`); `validUntil LocalDate`, `notes String` (500) (editáveis); `sentAt`, `decidedAt Instant` (`insertable = false, updatable = false`); `createdBy` (`updatable = false`); `createdAt`/`updatedAt` (somente leitura); `@Version Long` |
| `BudgetItem` (`budget_items`) | `@Immutable`, `Persistable`; `id`; `budgetId`, `procedureTypeId` (`updatable = false`); `description String` (`insertable = false, updatable = false`: copiada pelo trigger); `quantity Short`; `unitPrice BigDecimal` (`insertable = false, updatable = false`); `discountAmount BigDecimal`; `lineTotal BigDecimal` (`insertable = false, updatable = false`: coluna gerada); `position Short`. `version`/`updated_at` **não mapeados** (a linha nunca é atualizada; `PUT` substitui a lista inteira) |
| `BudgetDocument` (`budget_documents`) | `@Immutable`, `Persistable`; `id`; `budgetId`, `patientId`; `revision Short` (`insertable = false`: pelo trigger); `storageKey String` (120); `contentType` (fixo `application/pdf`); `sizeBytes Long`; `sha256 String` (64, **`varchar`**, nunca `char`); `generatedAt Instant` (`insertable = false`: default `now()`); `deletedAt Instant` (somente leitura: só a anonimização do banco o grava); `createdBy`; `createdAt` |
| `Installment` (`installments`) | `id`; `budgetId`, `patientId`, `number Short`, `amount BigDecimal` (todos `updatable = false`); `dueDate LocalDate` (editável); `status InstallmentStatus` (`OPEN`/`PAID`/`CANCELED`; **editável só por `cancel()`**: o service sempre carrega a entidade na própria transação antes de gravar, e o `@Version` falha se um trigger de pagamento mexeu na linha entre a leitura e a gravação); `createdBy`; `createdAt`/`updatedAt` (somente leitura); `@Version Long` |
| `Payment` (`payments`) | `@Immutable`, `Persistable`; `id`; `installmentId`; `kind PaymentKind` (`PAYMENT`/`REVERSAL`); `amount`; `method PaymentMethod` (`CASH`, `PIX`, `DEBIT_CARD`, `CREDIT_CARD`, `BANK_TRANSFER`, `OTHER`); `paidOn LocalDate`; `reversesPaymentId UUID`; `createdBy`; `createdAt` (`insertable = false`). **Sem `@Version`, sem `updated_at`**: a linha é só de inserção |
| `Appointment` (alterada) | + `procedureTypeId UUID` (coluna `procedure_type_id`, editável, sem `@ManyToOne`); o comentário "não mapeada" some |
| `Clinic` (sem mudança) | `name`, `phone`, `address`, `timezone` já mapeados; `ClinicIdentity` lê só esses |

Pontos de validação do mapeamento:
- `numeric(12,2)` → `BigDecimal` com `scale 2`; `date` → `LocalDate`; `timestamptz` → `Instant`; `smallint` → `Short`; `bigint version` → `Long`.
- Colunas omitidas do `INSERT` (`number`, `total_amount`, `description`, `unit_price`, `line_total`, `revision`, `sent_at`, `decided_at`) são **NOT NULL** no banco; o `NOT NULL` é verificado **depois** dos triggers `BEFORE INSERT`, que as preenchem (ponto 1 da seção 9).
- Em `Budget`, o `UPDATE` do Hibernate reenvia `discount_amount`, `valid_until`, `notes` e `appointment_id` com os **mesmos valores** nas transições de status: o banco aceita (compara com o antigo). Nunca enviar `number`, `total_amount`, `sent_at` e `decided_at` (por isso `updatable = false`).

### 2.3 Estado em memória

- **Stateless**, como nas fases anteriores; nenhum cache de preço, procedimento, fuso ou configuração.
- Únicos estados em memória, ambos por instância: `DocumentSlots` (semáforo de envios com PDF) e `DocumentRateLimiter` (Bucket4j, por usuário), no padrão do `UploadRateLimiter`. Perdem-se no restart; aceitável com 1 instância (mesma ressalva da Fase 1).
- O "agora" e o "hoje" vêm sempre do `Clock` injetável e de `ClinicZones.zone()`; nunca de `Instant.now()`/`LocalDate.now()`.
- Constantes em `CommercialRules` e `FinanceRules` (não são propriedades): 50 itens, quantidade 1–999, 60 parcelas, teto `999999.99`, janela de relatório de 366 dias, tamanho de página (orçamentos: padrão 20, máx. 50; parcelas e pagamentos: padrão 50, máx. 100), TTL da URL assinada (do `CrmProperties.Storage`, 60 s), limite do PDF (5 MB), data mínima `2000-01-01`.

---

## 3. Arquitetura de componentes (backend)

### 3.1 Componentes

| Componente | Responsabilidade |
|---|---|
| `Money` (`shared.money`) | `parse(String)` (regex `^\d{1,6}(\.\d{1,2})?$`, escala 2, 0 a 999999.99), `parsePositive`, `format(BigDecimal)` (`toPlainString`, escala 2), `splitDown(total, n)` (partes iguais em centavos, `RoundingMode.DOWN`, a diferença na última). Sem Spring; 100% testável |
| `ProcedureTypeService` | CRUD sem `DELETE` (criar, editar, ativar/inativar, listar, obter); só `DENTIST_ADMIN` escreve |
| `ProcedureTypeLookup` (interface pública) | Para `scheduling` e `commercial`: `requireActive(id)` (404 / 422 `PROCEDURE_INACTIVE`), `briefs(ids)` → `Map<UUID, ProcedureBrief(id, name, color)>` em **uma** consulta, `quotes(ids)` → `Map<UUID, ProcedureQuote(id, name, basePrice, active)>` em uma consulta |
| `BudgetService` | Criar, editar (substituição de itens), obter, listar e `discard`; usa `PatientLookup`, `AppointmentLookup`, `ProcedureTypeLookup` e `BudgetDraftValidator` |
| `BudgetDraftValidator` | Regra **pura** de limites e valores do rascunho: itens 1–50, quantidade, descontos, teto por linha/soma/total; recebe os preços (`ProcedureQuote`) e devolve a lista de erros com caminho (`items[2].discountAmount`) |
| `BudgetTransitions` | Tabela pura da máquina de estados (ação → origens, destino, papéis); sem Spring |
| `BudgetDecisionService` | `approve` e `reject` (só a partir de `SENT`, H3) |
| `BudgetDocumentService` | `send` (muda o status e gera o PDF), `regenerate` (nova revisão), listagem de revisões ativas e URL assinada; o **fluxo atômico H1** (seção 4.7) |
| `BudgetPdfRenderer` + `BudgetPdfModel` | Gera o PDF em memória com OpenPDF a partir de um modelo imutável; sem acesso a banco, Storage nem relógio; testável por extração de texto |
| `DocumentSlots` | Semáforo de envios simultâneos com PDF (padrão 3 de 10 conexões); `tryAcquire` com espera máxima de 2 s |
| `DocumentRateLimiter` + `DocumentRateLimitInterceptor` | 6 envios/revisões por minuto por usuário, em `POST /budgets/{id}/send` e `POST /budgets/{id}/documents` |
| `BudgetLookup` (interface pública) | Para `finance`: `requireApproved(budgetId)` (404 / 409), `totalOf`, `brief(ids)` → `BudgetBrief(id, number, patientId)`, `approvedBetween(ini, fim, limite)` e `approvedTotal(ini, fim)` (faturamento), `approvedOfPatient(patientId)` |
| `BudgetAnonymizationContributor` | Implementa `AnonymizationContributor` (de `patient`): devolve as `storage_key` dos PDFs do paciente e a contagem de orçamentos `DRAFT`/`SENT` |
| `InstallmentPlanner` | Regra **pura** do plano (modos `AUTO` e `MANUAL`), arredondamento e datas; sem Spring |
| `InstallmentPlanService` | Gerar e refazer o plano; trava o orçamento; traduz `CR009`/`CR011`/`23505` |
| `InstallmentService` | Ajustar vencimento; listar parcelas do orçamento (com `netPaid` e `balance`) |
| `PaymentService` | `register` e `reverse`; `paidOn` no fuso da clínica; traduz `CR010`/`23505` |
| `FinanceReportService` | `receivables`, `overdue`, `cashFlow`, `revenue`; períodos locais da clínica; só consultas |
| `PatientFinancialService` | Ficha financeira do paciente (orçamentos aprovados + parcelas + totais) |
| `ClinicIdentity` (interface pública em `clinic.service`) | `ClinicInfo current()`: nome, endereço e telefone da clínica do token, para o cabeçalho do PDF |
| `AnonymizationContributor` (SPI em `patient.service`) | `Collection<String> objectKeys(UUID patientId)` e `int openItems(UUID patientId)` (padrão `0`); o `PatientAnonymizationService` consulta todos os beans |
| `CommercialAuditLogger`, `CatalogAuditLogger`, `FinanceAuditLogger` | `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`; só identificadores, status e contagens (padrão do `SchedulingAuditLogger`); **nunca valores monetários** |
| `GlobalExceptionHandler` / `DatabaseErrors` (alterados) | Tabela da seção 5.1; nunca logam mensagem nem `DETAIL` do driver |

### 3.2 Endpoints

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`. Nenhum DTO aceita `id`, `clinicId`, `createdBy`, `role`, `status`, `number`, `totalAmount`, `unitPrice`, `description`, `lineTotal`, `sentAt`, `decidedAt`, `revision`, `kind` nem `storageKey`: campos extras do JSON são ignorados. Todo `{id}` de rota é resolvido dentro do tenant (outra clínica → 404). Respostas das rotas novas levam `Cache-Control: no-store`. Valores monetários são **texto decimal** (`"1234.50"`); datas civis (`from`, `to`, `dueDate`, `paidOn`, `validUntil`) são `AAAA-MM-DD` locais da clínica; instantes em ISO-8601 com deslocamento.

| Método | Rota | Papéis | Request | Response | Erros |
|---|---|---|---|---|---|
| GET | `/procedure-types?active=true\|false\|all` | D, R | padrão `true` | 200 lista `ProcedureTypeResponse` (sem paginação) | 400, 401 |
| GET | `/procedure-types/{id}` | D, R | — | 200 | 401, 404 |
| POST | `/procedure-types` | D | `{name, basePrice, defaultDurationMinutes, color}` | 201 + `Location` | 400, 401, 403, 409 |
| PUT | `/procedure-types/{id}` | D | `{name, basePrice, defaultDurationMinutes, color, version}` | 200 | 400, 401, 403, 404, 409 |
| PATCH | `/procedure-types/{id}/active` | D | `{active, version}` | 200 | 400, 401, 403, 404, 409 |
| POST | `/budgets` | D | `CreateBudgetRequest` | 201 `BudgetResponse` + `Location` | 400, 401, 403, 404, 409, 422 |
| GET | `/budgets?status&patientId&number&page&size` | D, R | `status` repetível; `number` exato; `size` ≤ 50 | 200 `PageResponse<BudgetSummaryResponse>` | 400, 401 |
| GET | `/budgets/{id}` | D, R | — | 200 `BudgetResponse` (itens e documentos ativos) | 401, 404 |
| PUT | `/budgets/{id}` | D | `UpdateBudgetRequest` (inclui `version`) | 200 `BudgetResponse` | 400, 401, 403, 404, 409, 422 |
| POST | `/budgets/{id}/send` | D | `{version}` | 200 `BudgetResponse` | 400, 401, 403, 404, 409, 422, 429, 503 |
| POST | `/budgets/{id}/approve`, `/reject`, `/discard` | D | `{version}` | 200 `BudgetResponse` | 400, 401, 403, 404, 409 |
| GET | `/budgets/{id}/documents` | D, R | — | 200 lista `DocumentResponse` (revisões ativas, maior primeiro) | 401, 404 |
| GET | `/budgets/{id}/documents/{docId}/download-url` | D, R | — | 200 `{url, expiresInSeconds}` | 401, 404, 503 |
| POST | `/budgets/{id}/documents` | D | sem corpo | 201 `DocumentResponse` (nova revisão) | 401, 403, 404, 409, 422, 429, 503 |
| GET | `/patients/{id}/budgets?page&size` | D, R | `size` ≤ 50 | 200 `PageResponse<BudgetSummaryResponse>` | 401, 404 |
| GET | `/budgets/{id}/installments` | D | — | 200 `InstallmentPlanResponse` | 401, 403, 404 |
| POST | `/budgets/{id}/installments` | D | `PlanRequest` (`AUTO` ou `MANUAL`) | 201 `InstallmentPlanResponse` | 400, 401, 403, 404, 409 |
| PUT | `/budgets/{id}/installments` | D | `PlanRequest` | 200 `InstallmentPlanResponse` | 400, 401, 403, 404, 409 |
| PATCH | `/installments/{id}/due-date` | D | `{dueDate, version}` | 200 `InstallmentResponse` | 400, 401, 403, 404, 409 |
| GET | `/installments/{id}/payments` | D | — | 200 lista `PaymentResponse` (cronológica) | 401, 403, 404 |
| POST | `/installments/{id}/payments` | D | `{amount, method, paidOn?}` | 201 `PaymentResultResponse` | 400, 401, 403, 404, 409, 422 |
| POST | `/payments/{id}/reversal` | D | sem corpo | 201 `PaymentResultResponse` | 401, 403, 404, 409 |
| GET | `/finance/receivables?from&to&page&size` | D | datas locais; `size` ≤ 100 | 200 `PageResponse<ReceivableResponse>` + `summary` | 400, 401, 403 |
| GET | `/finance/overdue?page&size` | D | `size` ≤ 100 | 200 `PageResponse<OverdueResponse>` + `summary` | 401, 403 |
| GET | `/finance/cash-flow?from&to` | D | janela ≤ 366 dias | 200 `CashFlowResponse` | 400, 401, 403 |
| GET | `/finance/revenue?from&to` | D | janela ≤ 366 dias | 200 `RevenueResponse` | 400, 401, 403 |
| GET | `/patients/{id}/financial` | D | — | 200 `PatientFinancialResponse` | 401, 403, 404 |

Rotas existentes alteradas: `POST /appointments` e `PUT /appointments/{id}` (+ `procedureTypeId`), `GET /appointments` e `GET /appointments/{id}` (+ `procedure`), `POST /patients/{id}/anonymize` (+ `CR006`, + `openBudgets`) e `GET /audit-log` (`tableName` ampliado).

Contratos de corpo (ilustrativos):

```json
// POST /budgets   (desconto e itens; number, total, unitPrice e description NUNCA vêm do cliente)
{ "patientId": "…", "appointmentId": null, "validUntil": "2026-12-15", "notes": "Pagamento em até 6x",
  "discountAmount": "50.00",
  "items": [ { "procedureTypeId": "…", "quantity": 2, "discountAmount": "0.00" },
             { "procedureTypeId": "…", "quantity": 1 } ] }

// PUT /budgets/{id}   (só DRAFT; substitui os itens inteiros; patientId não existe aqui)
{ "appointmentId": null, "validUntil": null, "notes": null, "discountAmount": "0.00",
  "items": [ { "procedureTypeId": "…", "quantity": 1 } ], "version": 3 }

// Resposta de orçamento (a recepção recebe o mesmo, sem parcelas por não existirem aqui)
{ "id": "…", "number": 12, "patient": { "id": "…", "name": "Maria Souza" }, "appointmentId": null,
  "status": "SENT", "subtotalAmount": "850.00", "discountAmount": "50.00", "totalAmount": "800.00",
  "validUntil": "2026-12-15", "notes": "Pagamento em até 6x", "sentAt": "2026-10-27T14:03:11Z", "decidedAt": null,
  "createdBy": "…", "createdAt": "…", "updatedAt": "…", "version": 5,
  "items": [ { "id": "…", "position": 1, "procedureTypeId": "…", "description": "Restauração", "quantity": 2,
               "unitPrice": "250.00", "discountAmount": "0.00", "lineTotal": "500.00" } ],
  "documents": [ { "id": "…", "revision": 1, "generatedAt": "…", "sizeBytes": 48211 } ] }

// POST /budgets/{id}/installments
{ "mode": "AUTO", "count": 6, "firstDueDate": "2026-11-27", "downPayment": "200.00" }
{ "mode": "MANUAL", "installments": [ { "amount": "300.00", "dueDate": "2026-11-27" },
                                      { "amount": "500.00", "dueDate": "2026-12-27" } ] }

// Resposta do plano
{ "budgetId": "…", "totalAmount": "800.00", "activeSum": "800.00",
  "installments": [ { "id": "…", "number": 1, "amount": "200.00", "dueDate": "2026-10-27", "status": "OPEN",
                      "netPaid": "0.00", "balance": "200.00", "version": 0 } ] }

// POST /installments/{id}/payments
{ "amount": "200.00", "method": "PIX", "paidOn": "2026-10-27" }
// 201
{ "payment": { "id": "…", "kind": "PAYMENT", "amount": "200.00", "method": "PIX", "paidOn": "2026-10-27",
               "reversesPaymentId": null, "createdBy": "…", "createdAt": "…" },
  "installment": { "id": "…", "status": "PAID", "netPaid": "200.00", "balance": "0.00", "version": 1 } }

// GET /finance/cash-flow
{ "from": "2026-10-01", "to": "2026-10-31", "netTotal": "4300.00",
  "byDay": [ { "date": "2026-10-05", "net": "300.00" } ],
  "byMethod": [ { "method": "PIX", "net": "2800.00" } ] }

// 422 de regra (ProblemDetail da Fase 0 + reason)
{ "type": "https://crm.local/problems/business-rule", "status": 422, "reason": "PROCEDURE_INACTIVE", "detail": "…" }
```

O `reason` do 422 desta fase: `PROCEDURE_INACTIVE`, `FUTURE_DATE`, `AMOUNT_OUT_OF_RANGE` (rede para o `23514` que escapou da validação), `PDF_TOO_LARGE`. Os `reason` da Fase 2 não mudam.

---

## 4. Lógica central e algoritmos

### 4.1 Sessão do banco, transações e releitura

- Sem mudança: o ponto único da Fase 1 (`TenantHibernateJpaDialect`) fixa `app.clinic_id`, `app.role` e `app.user_id` em toda transação, em **`READ COMMITTED`** (a numeração do orçamento depende disso; nenhum service muda o isolamento). Toda gravação precisa de `app.user_id` (a auditoria falha com `28000`) e usa `created_by = TenantContext.userId()`.
- **Releitura (`TriggerColumns.reload`).** Depois de gravar uma entidade com colunas de trigger: `entityManager.flush()` e `entityManager.refresh(entity)`. Em `Budget`, o `UPDATE` automático de `total_amount` feito por `refresh_budget_total` **não** altera `version` (o banco o garante); portanto o `@Version` do Hibernate não conflita com o próprio trigger.
- **Ordem de flush.** O Hibernate executa `INSERT` antes de `UPDATE` e `DELETE` no mesmo flush; onde a ordem importa (4.4 e 4.9), o service faz `flush()` explícito entre as etapas.
- **Travas de orçamento.** Operações que mudam o **conjunto** de itens, o status ou o plano de parcelas carregam o orçamento com `@Lock(PESSIMISTIC_WRITE)` numa consulta do repositório (o filtro de tenant e a RLS se aplicam; `find` por id não aplica o filtro). Sob RLS, `FOR UPDATE` exige a política de `UPDATE` visível; vale para `DENTIST_ADMIN`, o único papel que chama essas rotas.

### 4.2 Validação de entrada (antes do banco)

Aplicada em todas as rotas de escrita, devolvendo **todos** os erros de uma vez (400 `validation` com `errors[]`, no formato da Fase 0):
- **Dinheiro:** campos `String` com `@Pattern("^\\d{1,6}(\\.\\d{1,2})?$")` e `Money.parse`; recusa notação científica, sinal, vírgula, espaço e mais de 2 casas. Teto de R$ 999.999,99. `amount` de parcela e de pagamento ≥ 0,01.
- **Itens:** 1 a 50; `quantity` 1–999; `discountAmount` opcional (padrão `0.00`); `procedureTypeId` obrigatório.
- **Datas:** `AAAA-MM-DD`; `validUntil`, `dueDate` e `paidOn` ≥ `2000-01-01`.
- **Textos:** `notes` ≤ 500 (vazio ou só espaços vira `null`); `name` do procedimento 2–80 após `strip`; `color` `#RRGGBB`; duração 5–720 e múltipla de 5.
- Campos não previstos são ignorados e nunca gravados.

### 4.3 Tipos de procedimento (`catalog`)

- `POST`: valida (4.2), cria `ProcedureType(active = true, createdBy = userId)` com `UUID` novo e `save` (persist); `flush` com tradução de `23505` (`ux_ptype_clinic_name`) → 409 "Já existe um procedimento ativo com este nome.".
- `PUT`: carrega (404), `version` do corpo ≠ da entidade → 409, atualiza os quatro campos, `flush` (mesma tradução). Alterar o preço **não** toca orçamentos (snapshot do banco).
- `PATCH /active`: `version`; **reativar** um nome que colide com outro ativo falha em `23505` → 409.
- `GET` lista: `active=true` (padrão), `false` ou `all`; ordenada por `lower(name)` e `id`; sem paginação. Resposta traz `basePrice` e `defaultDurationMinutes` para o front propor duração e preço.
- `ProcedureTypeLookup.quotes` e `briefs` leem em **uma** consulta (`id IN :ids`), sem N+1.

### 4.4 Orçamento: criar, editar e descartar (`commercial`)

**`POST /budgets`** (`@PreAuthorize` D), tudo em **uma** transação:
1. Validar (4.2) → 400.
2. `PatientLookup.requireSchedulable(patientId)` → 404 (inexistente), 409 (anonimizado ou inativo).
3. Se `appointmentId` ≠ null: `AppointmentLookup.requireOfPatient` → 404 / 409.
4. `ProcedureTypeLookup.quotes(ids)`: id ausente → 404 "Procedimento não encontrado."; `active = false` → 422 `PROCEDURE_INACTIVE`.
5. `BudgetDraftValidator` com os preços: por linha `quantity × unitPrice − discount` em `[0, teto]`; soma das linhas ≤ teto; `discountAmount` do orçamento ≤ soma; total em `[0, teto]` → 400 com o caminho de cada campo.
6. Gravar na ordem exigida pelo banco (S7), com `flush` entre as etapas: **(a)** `Budget(status DRAFT, discountAmount 0)` com `UUID` novo, `persist`, `flush`; **(b)** itens (`position` 1..n na ordem recebida), `persist`, `flush`; **(c)** se o desconto do corpo for > 0, `setDiscountAmount` e `flush`. `reload` do orçamento (o trigger preencheu `number` e `total_amount`).
7. Montar `BudgetResponse` (itens relidos do banco, com `description`, `unitPrice`, `lineTotal`; paciente resumido em lote por `PatientLookup.briefs`). Evento `BudgetCreated`.
8. Traduções: `CR003`/`CR005` → 409 (corrida de anonimização/inativação); `23514` em `ck_items_line`/`ck_budgets_total` (preço mudou entre a leitura e a gravação) → 422 `AMOUNT_OUT_OF_RANGE`.

**`PUT /budgets/{id}`** (`@PreAuthorize` D):
1. Validar (4.2); carregar com trava (4.1) → 404; `version` do corpo ≠ da entidade → 409.
2. `status ≠ DRAFT` → 409 com o status atual (o `CR007` do banco é a rede). `patientId` não é aceito.
3. `appointmentId` novo: mesma checagem de 4.4-3; procedimentos: 4.4-4; `BudgetDraftValidator`: 4.4-5.
4. **Substituição dos itens, com `flush` entre as etapas** (a ordem importa porque o total intermediário não pode ficar negativo nem acima do teto, `ck_budgets_total`): **(a)** se o desconto atual > 0, zerar `discountAmount` e `flush`; **(b)** remover **todos** os itens e `flush` (o banco só aceita `DELETE` de item em `DRAFT`); **(c)** inserir os novos itens (`position` 1..n) e `flush`; **(d)** gravar o desconto final, `validUntil`, `notes`, `appointmentId` e `flush`. A unicidade de `(budget_id, position)` é adiável (S9), então reordenar na mesma transação passa.
5. `reload`; resposta 200 com o orçamento. Evento `BudgetUpdated`.

O `@Version` do orçamento sobe a cada `UPDATE` de entidade dentro da transação (até 3 por `PUT`); o cliente usa o `version` da **resposta**, nunca calcula o próximo.

**`POST /budgets/{id}/discard`** (D, `{version}`): `DRAFT → REJECTED`; o banco carimba `decided_at` e não exige itens para `REJECTED`. Não gera PDF. Evento `BudgetDiscarded`.

**Consultas:**
- `GET /budgets`: `Specification` (Criteria) com os predicados presentes (`status in :statuses`, `patientId`, `number`), ordenada por `createdAt DESC, id DESC`; `page` ≥ 0, `size` 1–50 (padrão 20) → 400 fora disso. `number` ≥ 1 usa `ux_budgets_clinic_number`. Evitar `(:p is null or …)` em JPQL: o PostgreSQL não infere o tipo de `uuid` nulo. Resumos de pacientes em lote.
- `GET /budgets/{id}`: `findById` no tenant (404) + itens por `position` + documentos ativos (`deleted_at IS NULL`, `revision DESC`) + paciente resumido. **Sem** `storage_key` em nenhuma resposta.
- `GET /patients/{id}/budgets`: `PatientLookup.requireExists` (404); mesma lista filtrada por paciente (`ix_budgets_patient`).

### 4.5 Máquina de estados do orçamento (`BudgetTransitions`)

Mesma tabela da seção 5 do PRD, validada **antes** do banco. Algoritmo comum de `send`, `approve`, `reject` e `discard`:
1. Papel (403) por `@PreAuthorize("hasRole('DENTIST_ADMIN')")` nos métodos.
2. Carregar com trava (404); `version` do corpo ≠ da entidade → 409.
3. Status atual fora das origens da ação → 409 com o status atual; `reject` em `DRAFT` → 409 "Use descartar para rascunhos."; `approve` em `DRAFT` → 409 "Envie o orçamento antes de aprová-lo."
4. `send` e `approve` com **zero itens ou total zero** → 409 "Inclua ao menos um item com valor maior que zero." (o `CR008` do banco é a rede).
5. Aplicar o destino, `flush`, `reload` (o banco carimba `sent_at`/`decided_at`), evento `BudgetStatusChanged(from, to)` e resposta 200.
6. `CR007` → 409 "Orçamento já enviado: não pode mais ser alterado."; `CR008` → 409 "Operação inválida para o status do orçamento."; 0 linhas/`OptimisticLockException` → 409 `STALE_VERSION`.

`approve` **não** gera parcelas (PRD): o front chama a rota de plano em seguida (4.8).

### 4.6 PDF do orçamento (`BudgetPdfRenderer`)

- **Entrada:** `BudgetPdfModel` imutável com: nome, endereço e telefone da clínica (`ClinicIdentity`); nome do paciente (`PatientLookup.briefs`); `number`, `revision`, data de envio (`sentAt` no fuso da clínica) e data de geração; `validUntil`; itens (`description`, `quantity`, `unitPrice`, `discountAmount`, `lineTotal`); `subtotal`, `discountAmount`, `total`; `notes`. **Sem CPF, sem telefone nem e-mail do paciente.**
- **Layout:** A4, margens de 40 pt, fonte base Helvetica (`BaseFont.CP1252`, que cobre `ã ç é ê ô º` e o `R$`), sem fonte externa (nada de `fontconfig` na imagem JRE enxuta). Cabeçalho da clínica; título "Orçamento nº N" (e "Revisão n" quando n > 1); data de envio; bloco do paciente; tabela `Descrição | Qtd | Preço unit. | Desconto | Total`; subtotal, desconto do orçamento e **total**; "Válido até dd/MM/aaaa" quando houver; observações; rodapé "Página x de y". Formatação pt-BR (`R$ 1.234,50`, `dd/MM/aaaa`).
- **Saída:** `byte[]` em memória (≤ 5 MB, `PDF_TOO_LARGE` 422 acima disso; um orçamento de 50 itens cabe em dezenas de KB). Metadados do PDF só com título e autor genérico (sem nome do paciente).
- O renderizador é **puro** e não depende de Spring; o `sha256` e o tamanho são calculados fora dele pelo `BudgetDocumentService`.

### 4.7 Envio e nova revisão (H1): `BudgetDocumentService`

O service **não** é `@Transactional`; usa `TransactionTemplate` (timeout de 20 s) para controlar a janela da transação e a compensação. Chaves: `UUID docId`, `UUID objectId`, `String key = StorageKeys.ofBudget(clinicId, patientId, objectId)`.

**`send(budgetId, version)`** (D):
1. `DocumentSlots.tryAcquire(2 s)`; sem vaga → 503 `unavailable` com `Retry-After: 5`. O limite por usuário (6/min) é do interceptor (429).
2. Dentro do `TransactionTemplate`:
   a. carregar o orçamento com trava, validar `version` e a transição (4.5), mudar para `SENT`, `flush`, `reload`;
   b. ler itens, paciente resumido e `ClinicIdentity`; montar o `BudgetPdfModel`; renderizar; calcular tamanho e `sha256` (hex minúsculo);
   c. **registrar a chave em um `AtomicReference` antes de gravar**, depois `documentStorage.save(key, bytes, "application/pdf", size)` (timeout de 10 s);
   d. `persist` do `BudgetDocument` (`createdBy = userId`), `flush`, `reload` (traz `revision` e `generated_at`);
   e. devolver a resposta do orçamento (já com o documento).
3. `COMMIT`. Depois dele, `DocumentSlots.release()` (no `finally`) e o evento `BudgetSent`.
4. **Compensação:** qualquer `RuntimeException` (do corpo ou do commit) com a chave registrada: se o corpo **terminou** e a exceção veio do commit (resultado incerto), conferir em transação própria e de leitura se existe `BudgetDocument` com essa `storage_key` — se existe, **não apagar**; caso contrário, `documentStorage.delete(key)`. Falha na compensação: evento `StorageCompensationFailed(budgetId)` e `log.error` só com identificadores; a resposta ao cliente continua sendo a do erro original.
5. Erros: `StorageException` → 503 (handler existente); `PDF_TOO_LARGE` → 422; `CR007`/`CR008`/`CR011` etc. → seção 5.1; qualquer outro → 500 `internal`. Em qualquer falha o `ROLLBACK` devolve o orçamento a `DRAFT` e não há linha em `budget_documents`.

**`regenerate(budgetId)`** (D, `POST /budgets/{id}/documents`): mesmo fluxo, **sem** mudar status: carrega o orçamento com trava (serializa revisões concorrentes e dá o número certo ao PDF), exige `SENT`, `APPROVED` ou `REJECTED` (`DRAFT` → 409, `CR008` é a rede), calcula `revision = max + 1` dos documentos (inclusive removidos) **só para imprimir no PDF** e deixa o banco atribuir a revisão real (devem coincidir graças à trava), grava e devolve 201 `DocumentResponse`. A revisão anterior é conservada.

**Janela de transação:** a transação segura **1 conexão** do pool (10) da leitura do orçamento até o commit; com o semáforo de 3 envios e o timeout de 10 s no Storage, o pior caso são 3 conexões por até ~15 s. As demais rotas continuam servidas pelas 7 restantes. O `statement_timeout` de 15 s do `crm_app` vale **por comando**, não pela transação.

**`documentStorage`:** um segundo bean `FileStorage` com o **mesmo** bucket e chave de serviço e `READ_TIMEOUT` de `crm.documents.upload-timeout` (10 s), distinto do bean dos exames (30 s). O `FileStorage` dos exames **não muda**.

**Download (`GET …/download-url`):** em transação de **leitura curta** busca a `storage_key` do documento ativo (`id`, `budgetId`); fora dela chama `createSignedUrl(key, ttl)` (60 s). Documento removido, de outro orçamento ou de outra clínica → 404; `StorageKeys.belongsTo(key, clinicId)` é conferido como nos exames. Nunca devolve a `storage_key`; nunca loga a URL.

### 4.8 Plano de parcelas (`InstallmentPlanner` + `InstallmentPlanService`)

**`InstallmentPlanner` (puro).** Entradas: `total`, `PlanRequest`, `today` (data local da clínica). Saída: lista de `(number, amount, dueDate)` ou erros 400.
- **`AUTO`:** `count` 1–60; `firstDueDate` ≥ `2000-01-01`.
  - Sem entrada: `base = total / count` arredondado `DOWN` a centavos; parcelas `1…count−1` valem `base`; a última vale `total − base × (count − 1)` (absorve os centavos). `dueDate(i) = firstDueDate.plusMonths(i − 1)` **calculado a partir da primeira data** (31/jan → 28/fev → 31/mar), nunca iterando mês a mês.
  - Com `downPayment` (> 0 e < `total`; exige `count ≥ 2`): a **parcela 1 é a entrada**, vence em `today`; as parcelas `2…count` dividem `total − downPayment` pela mesma regra (a última absorve), com `dueDate = firstDueDate.plusMonths(k)` para `k = 0…count − 2`.
  - Nenhuma parcela pode sair abaixo de R$ 0,01 → 400 "Parcelas pequenas demais para o total."
- **`MANUAL`:** 1 a 60 itens; cada `amount` em `[0,01; 999999,99]`; `dueDate` ≥ `2000-01-01`; a soma **deve ser igual** ao total (senão 400 com a diferença em centavos). Numera 1..n ordenando por `dueDate` e, em empate, pela ordem recebida.
- Garantia: Σ parcelas == total, sempre (teste de propriedade).

**`POST /budgets/{id}/installments`** (D):
1. Carregar o orçamento **com trava**; `status ≠ APPROVED` → 409 "Aprove o orçamento antes de gerar as parcelas." (`CR009` é a rede).
2. `PatientLookup.requireWritable(patientId)` (anonimizado → 409; **inativo passa**).
3. Existem parcelas não canceladas → 409 "O orçamento já tem plano de parcelas. Refaça o plano."
4. `today` = hoje na clínica; `InstallmentPlanner` → 400.
5. `persist` de cada `Installment(status OPEN, createdBy)` e **`flush`**; o commit dispara o `CR011` (constraint trigger adiável); o service usa `TransactionTemplate` e traduz a exceção do commit (seção 5.1). Reler as parcelas (`netPaid = 0`, `balance = amount`) e responder 201.

**`PUT /budgets/{id}/installments`** (D) — refazer:
1. Trava + `APPROVED` (como acima); carregar **todas as parcelas não canceladas**; nenhuma → 409 "Não há plano para refazer."
2. Qualquer parcela com líquido pago **> 0** (inclusive `PAID`) → 409 "Estorne os pagamentos antes de refazer o plano." (o `CR009` é a rede).
3. Marcar `cancel()` em **todas** as parcelas `OPEN` e `flush` **antes** de inserir as novas (senão o `INSERT` chega antes do `UPDATE` e colide com `ux_inst_budget_number` parcial).
4. `InstallmentPlanner` com o mesmo total; `persist` das novas (números 1..n reaproveitados) e `flush`; commit confere a soma (`CR011`).
5. Responder 200 com o plano novo (as canceladas não aparecem em `activeSum`).

**`PATCH /installments/{id}/due-date`** (D, `{dueDate, version}`): carregar (404); `version` ≠ → 409; status ≠ `OPEN` → 409 (`CR009` é a rede); `setDueDate`, `flush`, `reload`.

**`GET /budgets/{id}/installments`** (D): parcelas ordenadas por `number, id`, **inclusive canceladas** (`status`), com `netPaid` e `balance = amount − netPaid` (uma consulta agregada em `payments` para todas as parcelas, sem N+1), e `activeSum`.

**Não existe rota de cancelamento isolado** (quebraria a soma): o cancelamento acontece só dentro de refazer o plano.

### 4.9 Pagamentos e estornos (`PaymentService`)

**`POST /installments/{id}/payments`** (D):
1. Validar (4.2); `method` fora da lista → 400.
2. Carregar a parcela por **projeção** (`id`, `patientId`, `status`, `amount`) em consulta JPQL (não como entidade: o trigger vai atualizar a linha e a entidade ficaria velha) → 404.
3. `PatientLookup.requireWritable(patientId)`: anonimizado → 409; **inativo passa**.
4. `paidOn`: ausente = hoje na clínica; **maior que hoje** → 422 `FUTURE_DATE`.
5. `persist` de `Payment(kind PAYMENT, createdBy)` e `flush`: o banco trava a parcela (`FOR NO KEY UPDATE`), confere o saldo e atualiza o `status` (e `version + 1`) por trigger. `CR010` → 409 "Pagamento inválido: valor acima do saldo ou parcela cancelada."
6. Reler a projeção da parcela e o líquido em uma consulta; responder 201 com `payment` e `installment` (`status`, `netPaid`, `balance`, `version`).

**`POST /payments/{id}/reversal`** (D):
1. Carregar o pagamento (404); `kind ≠ PAYMENT` → 409 "Só é possível estornar um pagamento."; já existe estorno (`existsByReversesPaymentId`) → 409 "Este pagamento já foi estornado." (o `23505` em `ux_pay_reverses` é a rede, com a mesma mensagem).
2. `PatientLookup.requireWritable` do paciente da parcela.
3. `persist` de `Payment(kind REVERSAL, amount = original, method = original, paidOn = hoje na clínica, installmentId = original, reversesPaymentId = id)` e `flush`; o banco devolve a parcela a `OPEN` se estava `PAID`.
4. Responder 201 com o estorno e a parcela atualizada.

Nunca há `UPDATE` nem `DELETE` de pagamento: a entidade é `@Immutable` e o repositório só expõe leitura e `persist`.

### 4.10 Consultas do financeiro (`FinanceReportService`, só `DENTIST_ADMIN`)

Todas em `@Transactional(readOnly = true)`, JPQL com o filtro do tenant, **sem SQL nativo**; datas de entrada `AAAA-MM-DD` locais; `zone = ClinicZones.zone()`; `today = LocalDate.now(clock.withZone(zone))`; janela `to ≥ from` e ≤ 366 dias (inclusive as duas pontas) → 400.

| Consulta | Regra | Índice esperado |
|---|---|---|
| `receivables` | parcelas `OPEN` com `dueDate` entre `from` e `to`; ordenadas por `dueDate, id`; página (`size` ≤ 100); `summary {count, balanceTotal}` por consulta agregada | `ix_inst_open_due` |
| `overdue` | parcelas `OPEN` com `dueDate < today`; mais antigas primeiro; `daysOverdue = today − dueDate` calculado em Java; `summary {count, balanceTotal}` | `ix_inst_open_due` |
| `cash-flow` | `SUM(CASE kind WHEN PAYMENT THEN amount ELSE −amount END)` com `paidOn` em `[from, to]`, agrupado por `paidOn` e por `method` (**regime de caixa**) | `ix_pay_clinic_paid` |
| `revenue` | orçamentos `APPROVED` com `decidedAt` em `[from 00:00, to + 1 dia 00:00)` **no fuso da clínica**, convertido para `Instant` (**competência**); `count`, `totalAmount` e lista resumida (até 200, `truncated`) por `BudgetLookup` | `ix_budgets_decided` |

- O saldo e `netPaid` das páginas vêm de **uma** consulta agregada em `payments` por `installmentId IN :ids` (`ix_pay_installment`); pacientes por `PatientLookup.briefs`; números de orçamento por `BudgetLookup.brief`.
- Predicados com `=`, `<`, `>=` e `OR` de igualdades (nunca `status IN (...)` em predicado que precise do índice parcial), como na lição da Fase 2.
- `PatientFinancialService`: orçamentos `APPROVED` do paciente (`BudgetLookup.approvedOfPatient`) + todas as parcelas do paciente (`ix_inst_patient`, limite de 1000, `truncated`) + totais (`approvedTotal`, `activeInstallmentsTotal`, `paid`, `balance`, `overdue`). Paciente inativo ou anonimizado continua consultável.
- Resposta com `Cache-Control: no-store`.

### 4.11 Procedimento na agenda (`scheduling`)

- `CreateAppointmentRequest` e `RescheduleRequest` ganham `UUID procedureTypeId` (opcional). `AppointmentService.create/reschedule` recebem o id e, **quando preenchido**, chamam `ProcedureTypeLookup.requireActive` (404 / 422 `PROCEDURE_INACTIVE`) **antes** de gravar.
- **Semântica do `PUT` (divergência D1, seção 10):** `procedureTypeId` **substitui** o valor atual: ausente ou `null` remove o vínculo e o cliente reenvia o atual para mantê-lo, como já ocorre com `notes` e com o `appointmentId` da nota. Evita um estado de três valores no JSON (ausente × nulo × valor).
- `AppointmentResponse` ganha `procedure {id, name, color}` (nulo se não houver), obtido por `ProcedureTypeLookup.briefs` em lote junto com os pacientes (listagem sem N+1). `AppointmentView` carrega o mapa.
- **Não** muda: máquina de status, conflito, regra de horário, histórico do paciente, nem a duração (`endsAt` segue obrigatório; o front propõe com `defaultDurationMinutes`).

### 4.12 Anonimização de paciente

`PatientAnonymizationService.anonymize`:
1. Fase de banco (uma transação): `SELECT core.anonymize_patient(:id)` e, **na mesma transação e depois da função**, a lista de chaves = `files.storageKeysOf(patientId)` ∪ `objectKeys(patientId)` de cada `AnonymizationContributor` (os PDFs marcados com `deleted_at` pela função) e `openItems` somados (orçamentos `DRAFT`/`SENT`).
2. Fase de Storage (depois do commit): `files.purgeObjects(allKeys)` — o `SupabaseFileStorage.deleteAll` valida cada chave por `StorageKeys.require`, que passa a aceitar as duas formas (4.13). Falha → `PENDING` (comportamento atual).
3. Tradução de `CR006` → 409 "Quite ou cancele as parcelas em aberto antes de anonimizar." (a precedência `CR002 → CR004 → CR006` é do banco).
4. `AnonymizationResponse(anonymizedAt, storageCleanup, openBudgets)`; `openBudgets` é aviso, não bloqueia.
5. Orçamentos, itens, parcelas e pagamentos permanecem; `budgets.notes` fica `NULL`; PDFs ficam `deleted_at` e saem das listagens e do download (404).

`BudgetAnonymizationContributor` vive em `commercial` e é descoberto por injeção de `List<AnonymizationContributor>`; `patient` nunca importa `commercial`.

### 4.13 Chave de Storage (`StorageKeys`)

- `of(clinicId, patientId, fileId)` continua produzindo `<clinic>/<patient>/<uuid>`; **novo** `ofBudget(clinicId, patientId, objectId)` produz `<clinic>/<patient>/budgets/<uuid>`.
- `require(key)` aceita as **duas** formas (três UUIDs, ou dois UUIDs + literal `budgets` + UUID). Qualquer outra → `StorageException`. `belongsTo(key, clinicId)` continua por prefixo.
- `storage_key` é `varchar(120)`; `<uuid>/<uuid>/budgets/<uuid>` tem 36 + 1 + 36 + 1 + 7 + 1 + 36 = **118** caracteres, então cabe (ponto 10 da seção 9).

### 4.14 Auditoria

- `AuditQueryService.TABLES` ganha `budgets`, `installments`, `payments` e `budget_documents` (a lista continua fechada: outro nome → 400). `procedure_types` e `budget_items` **não** são auditados.
- O backend não escreve em `audit_log`; os triggers do banco gravam `INSERT`/`UPDATE`/`ANONYMIZE` sem valores. A troca automática de `status` da parcela aparece como `UPDATE`.
- Os `*AuditLogger` da fase só registram identificadores, status e contagens **após o commit**.

### 4.15 Isolamento de tenant e papel

Mesmo desenho da Fase 1: filtro Hibernate + RLS + papel reforçado pelo banco. Todas as consultas novas são JPQL/Criteria ou derivadas; **nenhum SQL nativo** em tabela com `clinic_id`. `EntityManager.find` e `refresh` por id não aplicam o filtro: a RLS protege, e o service confere `entity.getClinicId()` contra o tenant onde isso for usado. A recepção é barrada **antes** do banco pelo `SecurityConfig` e por `@PreAuthorize`; `42501` num `INSERT`/`UPDATE` é defeito do backend (500).

---

## 5. Erros e casos de borda

Formato `application/problem+json` da Fase 0. Nenhuma mensagem contém valor monetário, nome de paciente, observação, chave de objeto, URL assinada, UUID de outra clínica nem o `DETAIL` do driver.

### 5.1 Tradução de erros do banco e de regras

| Origem | Quando | HTTP / tipo | Mensagem |
|---|---|---|---|
| `CR003` | Orçamento, parcela ou pagamento para paciente anonimizado | 409 `conflict` | "Paciente anonimizado." |
| `CR005` | Orçamento novo para paciente inativo | 409 `conflict` | "Paciente inativo." |
| `CR006` | Anonimizar com parcela `OPEN` | 409 `conflict` | "Quite ou cancele as parcelas em aberto antes de anonimizar." |
| `CR007` | Alterar item ou conteúdo de orçamento fora de `DRAFT` | 409 `conflict` | "Orçamento já enviado: não pode mais ser alterado." |
| `CR008` | Status inválido, enviar/aprovar sem itens ou com total zero, PDF de rascunho | 409 `conflict` | "Operação inválida para o status do orçamento." |
| `CR009` | Regra da parcela (orçamento não aprovado, parcela terminal ou paga alterada, cancelamento com saldo pago) | 409 `conflict` | "Operação inválida para o estado da parcela." |
| `CR010` | Pagamento acima do saldo, parcela cancelada, estorno inválido | 409 `conflict` | "Pagamento inválido: valor acima do saldo ou parcela cancelada." |
| `CR011` | Soma das parcelas ≠ total, **no commit** | 409 `conflict` | "As parcelas não somam o total do orçamento." |
| `23505` + `ux_ptype_clinic_name` | Nome de procedimento ativo repetido | 409 `conflict` | "Já existe um procedimento ativo com este nome." |
| `23505` + `ux_pay_reverses` | Segundo estorno do mesmo pagamento | 409 `conflict` | "Este pagamento já foi estornado." |
| `23505` + `ux_inst_budget_number` | Dois planos simultâneos (a trava de 4.8 deve impedir) | 409 `conflict` | "O plano de parcelas foi alterado. Recarregue." |
| `23505` + `ux_budgets_clinic_number` / `ux_items_budget_position` / `ux_bdoc_*` | Defeito do backend (numeração, posição, revisão) | 500 `internal` | genérica; log só com a constraint |
| `23514` + `ck_items_line` / `ck_budgets_total` | Valor fora do teto que escapou da validação | 422 `business-rule` (`AMOUNT_OUT_OF_RANGE`) | "Valores do orçamento fora do limite." |
| outras `23514` / `23503` | Validação do backend deixou passar | 500 `internal` | genérica; log com o **nome** da constraint |
| `42501` | Papel/política negou, ou `UPDATE`/`DELETE`/`TRUNCATE` em `payments` | 403 se o backend não barrou antes; **defeito → 500** | texto padrão |
| `28000` | Gravação sem `app.user_id` | 500 `internal` | genérica |
| 0 linhas por RLS ou `OptimisticLockException` | Linha mudou entre leitura e gravação | 409 `conflict` | `STALE_VERSION` |
| `StorageException` | Storage fora, lento ou recusando | 503 `unavailable` | "Serviço temporariamente indisponível. Tente novamente em instantes." |
| Sem vaga em `DocumentSlots` | Muitos envios simultâneos | 503 `unavailable` + `Retry-After: 5` | idem |
| `RateLimitedException` | Mais de 6 envios/revisões por minuto | 429 `rate-limited` + `Retry-After` | "Muitos envios de orçamento. Tente novamente em instantes." |
| Regra de negócio | `reason` ∈ {`PROCEDURE_INACTIVE`, `FUTURE_DATE`, `AMOUNT_OUT_OF_RANGE`, `PDF_TOO_LARGE`} | 422 `business-rule` | frase por `reason` |

**Mudanças no `GlobalExceptionHandler` (todas compatíveis):**
1. Os casos acima entram no `switch` de `handleDatabase` e em `uniqueViolation` (que hoje tem só `ux_bh_clinic_weekday` e `ux_clinic_holidays_date`).
2. **Novo `@ExceptionHandler(TransactionSystemException.class)`** que extrai o `SQLState` da cadeia de causas (`DatabaseErrors.sqlState` já percorre a cadeia) e delega ao mesmo `switch`; sem `SQLState` conhecido, mantém o 500 `internal`. É o único caminho do `CR011`.
3. A tradução específica por mensagem continua no **service** (mensagem própria + `flush` explícito); o handler global cobre a exceção que só estoura no commit.

### 5.2 Cenários

| Cenário | Tratamento |
|---|---|
| Recepção chama qualquer rota de escrita da fase, de parcela, pagamento, relatório ou ficha | 403 pela regra de rota e por `@PreAuthorize`, sem tocar o banco |
| Orçamento ou parcela de outra clínica | 404 |
| `version` defasada | 409 `STALE_VERSION` |
| Criar orçamento para paciente inexistente / anonimizado / inativo | 404 / 409 / 409 |
| `appointmentId` de outro paciente / inexistente | 409 / 404 |
| Procedimento inativo em item ou agendamento | 422 `PROCEDURE_INACTIVE` |
| Item com desconto maior que a linha; desconto do orçamento maior que a soma; total > teto | 400 com o caminho do campo |
| `PUT` de orçamento enviado, aprovado ou recusado | 409 com o status atual |
| Dois `POST /budgets` simultâneos na mesma clínica | Números distintos e sequenciais; sem 500 |
| Item editado enquanto outra requisição envia o orçamento | A trava de linha serializa: ou o item entra no PDF, ou o `PUT` recebe 409 |
| `send` sem itens / com total zero | 409 "Inclua ao menos um item com valor maior que zero." |
| `approve` de `DRAFT`; `reject` de `DRAFT` | 409 (mensagens próprias) |
| `send` com falha no Storage | `ROLLBACK`, orçamento volta a `DRAFT`, nenhuma linha de documento, objeto apagado, 503 |
| `send` com falha na gravação de `budget_documents` | `ROLLBACK`, objeto apagado, 500 |
| Commit com resultado incerto | Confere `budget_documents` pela `storage_key`; só apaga o objeto se **não** houver linha |
| Falha na compensação | Evento `StorageCompensationFailed`; objeto órfão registrado para limpeza manual |
| PDF > 5 MB | 422 `PDF_TOO_LARGE`; nada gravado |
| 7º envio em 1 minuto pelo mesmo usuário | 429 |
| Download de documento removido / de outra clínica | 404 |
| Plano com soma ≠ total (modo manual) | 400 com a diferença; nada gravado |
| Plano `AUTO` com parcela < R$ 0,01 | 400 |
| Gerar plano em orçamento não `APPROVED` | 409 |
| Gerar plano onde já existe | 409; refazer é `PUT` |
| Refazer plano com pagamento líquido > 0 | 409 "Estorne os pagamentos antes de refazer o plano." |
| `CR011` por defeito de cálculo ou corrida | 409 no commit (via `TransactionSystemException`); nada persistido |
| Ajustar vencimento de parcela `PAID` / `CANCELED` | 409 |
| Pagamento maior que o saldo; em parcela cancelada | 409 (`CR010`) |
| Dois pagamentos simultâneos no último saldo | Um 201 e um 409; sem 500 |
| Pagamento com data futura | 422 `FUTURE_DATE` |
| Pagamento parcial e depois o restante | Passam; a parcela vira `PAID` no último (status é do banco) |
| Estornar estorno / estornar duas vezes | 409 / 409 |
| Estorno de pagamento quitado | Parcela volta a `OPEN` |
| Pagamento/parcela de paciente inativo | Aceito; de anonimizado, 409 |
| Anonimizar com parcela `OPEN` | 409 (`CR006`); com orçamento `SENT` sem parcelas passa e `openBudgets` informa |
| Anonimizar com falha no Storage | Anonimização mantida; `storageCleanup = PENDING` |
| Auditoria falha (`28000`) | Operação inteira reverte; 500 sem detalhes |
| `from`/`to` ausentes, mal formatados, janela > 366 dias, `to < from` | 400 com o campo |
| `page`/`size` fora da faixa; `status` desconhecido; `number` < 1 | 400 |
| JSON com valor numérico no lugar de texto decimal | 400 (ponto 6 da seção 9 define se o Jackson 3 o coage) |
| Banco lento/indisponível | 503 `unavailable` (handler da Fase 0) |

---

## 6. Segurança e desempenho

### 6.1 Segurança

- **Papel só do JWT**; `clinic_id` nunca vem do corpo, da URL ou do header. `SecurityConfig` ganha as regras por rota abaixo (mais específico antes do mais geral; validar o casamento dos *matchers* no primeiro build, ponto 11 da seção 9):
  - `POST|PUT|PATCH /api/v1/procedure-types`, `/api/v1/procedure-types/**` → D;
  - `POST /api/v1/budgets`, `POST /api/v1/budgets/**` e `PUT /api/v1/budgets/**` → D (cobre criar, editar, `send`, `approve`, `reject`, `discard`, `documents` e o plano);
  - `GET /api/v1/budgets/*/installments` → D;
  - `/api/v1/installments/**`, `/api/v1/payments/**`, `/api/v1/finance/**` (todos os métodos) e `/api/v1/patients/*/financial` → D;
  - as leituras de `procedure-types`, `budgets`, `budgets/*/documents` (inclusive `download-url`) e `patients/*/budgets` caem em `authenticated()` (D e R).
- **`@PreAuthorize` nos services** como segunda barreira: `ProcedureTypeService` (escritas), `BudgetService` (escritas), `BudgetDecisionService`, `BudgetDocumentService.send/regenerate`, e **toda** a `finance` (`hasRole('DENTIST_ADMIN')` no nível da classe). Leituras de orçamento e documentos: `hasAnyRole`.
- **DTOs** sem `clinicId`, `createdBy`, `role`, `status`, `id`, `number`, `totalAmount`, `unitPrice`, `description`, `lineTotal`, `sentAt`, `decidedAt`, `revision`, `kind` e `storageKey` na entrada; `clinicId` e `storageKey` nunca na saída.
- **Preço e total não forjáveis:** o backend não envia `unit_price`/`description`/`total_amount`; o banco os sobrescreve (S3, S4).
- **PDF:** conteúdo gerado só pelo servidor (nunca recebido do cliente); sem CPF; objeto com chave opaca no bucket privado; acesso apenas por URL assinada de 60 s depois de checar tenant e papel; `sha256` gravado para detectar troca. A anonimização apaga os objetos.
- **Log:** `CommercialAuditLogger`, `CatalogAuditLogger` e `FinanceAuditLogger` registram só `budgetId`, `installmentId`, `paymentId`, `patientId`, `userId`, status, `revision` e contagens. **Nunca valor monetário, nome, observação, chave de objeto nem URL.** O `DETAIL` dos erros do banco nunca é logado nem devolvido. `toString` de `Upload`-like (`BudgetPdfModel`, `PaymentRequest`) não imprime dados.
- **Cache:** `Cache-Control: no-store` em `/procedure-types`, `/budgets`, `/installments`, `/payments` e `/finance` (estender `ClinicalCacheHeaders`; `/patients/**` já está coberto).
- **Texto livre:** `budgets.notes` (≤ 500, texto claro, vai ao PDF) e `appointments.notes`; a documentação da API avisa que **não leva dado clínico**.
- **Limites:** 6 envios ou revisões por minuto por usuário (429); 3 envios com PDF em paralelo (503); upload de 10 s; PDF ≤ 5 MB; `size` máx. 50 (orçamentos) e 100 (parcelas/pagamentos).
- **Defesa em profundidade provada** em Testcontainers (filtro desligado, papel forjado), como nas Fases 1 e 2.
- **Risco aceito herdado:** o banco confia no `app.role` informado pelo backend; `crm_app` pode tomar o lock consultivo da numeração de qualquer clínica.

### 6.2 Desempenho

- Metas (ambiente local, 4 requisições concorrentes, `support/PerfLimits` dobra o limite com `CI`, sobrescrevível por `-Dperf.limit-ms`): `receivables`, `overdue`, `cash-flow`, `revenue` e `GET /patients/{id}/budgets` com p95 ≤ **100 ms** com 5 mil parcelas e 5 mil pagamentos na clínica (e 100 mil de cada no banco); sem `Seq Scan` nos planos (banco: ≤ 10 ms; ≤ 5 ms na lista do paciente).
- `POST /installments/{id}/payments` p95 ≤ **100 ms**; criar orçamento de 20 itens p95 ≤ **250 ms** (banco: ≤ 100 ms); **envio** (PDF de 20 itens + Storage local) p95 ≤ **1,5 s**; geração do PDF em memória ≤ 300 ms.
- **Sem N+1:** pacientes, procedimentos e números de orçamento em lote; `netPaid` por consulta agregada; itens e documentos por uma consulta cada.
- Custos por escrita (herdados do banco): item = `SELECT … FOR NO KEY UPDATE` do pai + `UPDATE` do orçamento; pagamento = lock da parcela + soma por índice + `UPDATE` da parcela quando o status muda; plano de 60 parcelas = 60 verificações de soma no `COMMIT`.
- **Pool:** o envio segura 1 conexão por até ~15 s (4.7); com `DocumentSlots = 3` e pool de 10, as demais rotas mantêm ≥ 7 conexões. Meta de teste: com 3 envios lentos simulados, uma leitura de orçamento ainda responde em p95 ≤ 200 ms.

---

## 7. Configuração

**Dependência nova:** `com.github.librepdf:openpdf` (série 2.x) em `pom.xml`. Sem fonte externa: a imagem JRE enxuta do `Dockerfile` **não muda**.

**Propriedades novas (todas com padrão; nenhuma variável de ambiente obrigatória):**

```yaml
crm:
  documents:
    upload-timeout: 10s        # leitura do Storage no envio do PDF (bean documentStorage)
    max-concurrent: 3          # envios com PDF simultâneos (DocumentSlots)
    sends-per-minute: 6        # por usuário (DocumentRateLimiter)
    transaction-timeout: 20s   # teto da transação do envio
```

`CrmProperties` ganha o record `Documents(Duration uploadTimeout, int maxConcurrent, int sendsPerMinute, Duration transactionTimeout)` com os padrões acima. `.env.example`, `Dockerfile` e `docker-compose.yml` não mudam; `StartupConfigValidator` não muda (em `prod` o Supabase Storage continua obrigatório, o que cobre o `documentStorage`). O `LocalFileStorage` atende o `documentStorage` em dev e teste.

Observação operacional: o Testcontainers dos ITs aplica `database/bootstrap` e `database/migrations` **V001–V029**; o auxiliar de IT (`Phase3ItSupport`, sucessor do `Phase2ItSupport`) deve apontar para o conjunto completo.

---

## 8. Estratégia de testes (para o `dev-engineer-java`)

| Camada | Ferramenta | Casos críticos |
|---|---|---|
| Unitário | JUnit 5 + Mockito | `Money` (parse, rejeita `1e3`, `-1`, `1,5`, 3 casas, acima do teto; `splitDown` com a diferença na última); `InstallmentPlanner` (tabela: AUTO com e sem entrada, 1 e 60 parcelas, fim de mês, `plusMonths` da primeira data, parcela < R$ 0,01, MANUAL com soma errada, ordenação e numeração; **propriedade:** Σ == total para totais e contagens aleatórias); `BudgetDraftValidator` (limites de linha, soma, desconto, total); `BudgetTransitions` (toda a tabela da seção 5 do PRD, inclusive as proibidas e `reject` de `DRAFT`); `BudgetPdfRenderer` (texto extraído contém número, itens, total e observações, **não** contém CPF; acentos; 50 itens; tamanho); `BudgetService` (ordem 400→404→409→422→persist, substituição de itens com `flush` entre as etapas); `BudgetDocumentService` (compensação em cada ponto de falha, commit incerto, semáforo, limite); `PaymentService` (`paidOn`, futuro, `CR010`); `InstallmentPlanService` (soma, trava, refazer); `DatabaseErrors`/handler (`CR006`–`CR011`, `23505` novas, `TransactionSystemException`) |
| Web | `@WebMvcTest` + `spring-security-test` | **Matriz papel × endpoint** completa (401/403/2xx) para todas as rotas da seção 3.2, em `Phase3Routes`; DTOs ignoram `clinicId`/`status`/`number`/`totalAmount`/`unitPrice`/`kind`; valor numérico no lugar de texto → 400; `reason` no 422; `Retry-After` em 429/503; `Cache-Control: no-store`; formato `ProblemDetail` |
| Integração | Testcontainers PostgreSQL com `database/bootstrap` e `database/migrations` (V001–V029) | (1) `ddl-auto=validate` com as entidades novas e a `Appointment` ampliada (fecha o teste 120 do banco e o 84 da Fase 2); (2) **invariantes de dinheiro** (propriedade): ≥ 200 sequências aleatórias de criar/editar/enviar/aprovar/planejar/pagar/estornar, conferindo (a)–(d) da meta 2 do PRD ao fim de cada sequência; (3) **concorrência:** 2 pagamentos no último saldo (20×, um 201 e um 409), parcelas diferentes passam, 2 `POST /budgets` com números distintos (20×), item × envio, 2 planos simultâneos, 2 revisões simultâneas; (4) **atomicidade do envio** com `FileStorage` de teste que falha em `save`/`delete`, `INSERT` que falha e commit que falha: `DRAFT`, sem linha, sem objeto órfão; (5) **`CR011` no commit** com plano forçado errado → 409, nada persistido; (6) **isolamento com filtro desligado:** duas clínicas, 0 linhas da outra nas seis tabelas e nos relatórios, inclusive SQL nativo; (7) **papel forjado** (`app.role = RECEPTIONIST`, filtro desligado): `installments`/`payments` invisíveis, nenhuma escrita em procedimento/orçamento/item/PDF; papel ausente ou inválido nega tudo; (8) anonimização: `CR006`, `notes` zerados, PDFs `deleted_at`, objetos apagados, `PENDING` com Storage falhando, financeiro intacto; (9) `GET /audit-log` das quatro tabelas e 400 para `procedure_types`; (10) paciente inativo: orçamento novo 409, plano e pagamento passam; (11) relatórios: caixa × competência, fuso da clínica na virada do mês, estorno no líquido; (12) agenda com `procedureTypeId` (substitui no `PUT`, inativo 422, resumo em lote); (13) **higiene de log** (nenhum valor, nome, observação, chave ou URL em log, `toString` ou erro), validada por mutação; (14) **desempenho:** 5 mil parcelas e 5 mil pagamentos semeados por `JdbcTemplate` como dono, p95 e plano sem `Seq Scan`; (15) pool: 3 envios lentos simulados não derrubam uma leitura; (16) a suíte das Fases 1 e 2 reexecutada |
| Arquitetura | ArchUnit (`Phase3ArchitectureTest`) | `catalog`, `commercial` e `finance` sem ciclo e sem repositório alheio; `finance → commercial` só por `BudgetLookup`; `scheduling → catalog` só por `ProcedureTypeLookup`; `patient` não depende de `commercial` nem de `finance`; sem `@Data`/`@ToString`/`@EqualsAndHashCode` nas entidades novas; `BudgetPdfRenderer`, `InstallmentPlanner`, `Money` e `BudgetTransitions` sem import de Spring |

**Critérios de saída da Fase 3:** `mvn verify` verde em máquina limpa (Docker ligado); matriz de papéis 100%; testes de invariantes, concorrência e atomicidade do envio verdes; isolamento e papel forjado verdes; cobertura ≥ 80% nas classes de serviço de `catalog`, `commercial` e `finance` e nas classes de serviço alteradas de `scheduling`, `patient` e `record`; nenhum valor, nome, observação, chave ou URL em log; histórias US-P3-01 a US-P3-15 atendidas.

---

## 9. Pontos a validar antes de codar

1. **Colunas omitidas do `INSERT`** (`number`, `total_amount`, `description`, `unit_price`, `line_total`, `revision`, `sent_at`, `decided_at`): confirmar no primeiro IT que o `NOT NULL` só é verificado depois do `BEFORE INSERT` e que a coluna gerada aceita a omissão.
2. **`flush` + `refresh` do `Budget`** após trigger: o `refresh_budget_total` não incrementa `version`; confirmar que a entidade relida traz `total_amount`/carimbos e que o `@Version` do Hibernate não gera conflito próprio.
3. **Ordem do flush do Hibernate** (`INSERT` antes de `UPDATE`/`DELETE`): confirmar que os `flush()` explícitos de 4.4 e 4.8 evitam `ck_budgets_total` negativo e `ux_inst_budget_number`.
4. **`CR011` no commit:** confirmar qual exceção sobe (`TransactionSystemException`, `JpaSystemException` ou `RollbackException`) e que `DatabaseErrors.sqlState` a encontra na cadeia de causas do Hibernate 7; é o único caminho do código.
5. **Advisory lock e constraint triggers adiáveis atrás do pooler 6543** (ponto 1 da seção 10 do banco): só se confirma de fato com este backend contra o Supabase real; rodar um smoke de numeração e de plano de parcelas na porta 6543 ao fim da fase.
6. **Jackson 3 e valor monetário:** confirmar se um número JSON (`1234.5`) em campo `String` é coagido ou recusado; a spec exige **recusa** (400). Ajustar `CoercionConfig` se necessário.
7. **OpenPDF:** fixar a versão da série 2.x compatível com Java 21 e confirmar o pacote (`com.lowagie.text`), a codificação `CP1252` (acentos e `R$`), a numeração de páginas "x de y" e que o PDF não grava a data/nome do sistema operacional nos metadados.
8. **`PESSIMISTIC_WRITE` sob RLS e filtro:** confirmar que a consulta com `@Lock` aplica o filtro do tenant, que o `FOR UPDATE` funciona com a política de `UPDATE` do dentista e que não gera linha de auditoria (como a trava da nota na Fase 2).
9. **Pool durante o envio:** medir o tempo de retenção da conexão com o `LocalFileStorage` e com um Storage lento simulado; confirmar que `DocumentSlots = 3` e o pool de 10 bastam.
10. **`StorageKeys` e o Supabase:** o caminho `<clinic>/<patient>/budgets/<uuid>` (118 caracteres, cabe em `varchar(120)`) é aceito pela API de Storage e pelo `CHECK` de `storage_key`; `deleteAll` valida as duas formas; a URL assinada funciona com o segmento `budgets`.
11. **Spring Security 7:** casamento dos *matchers* (`/budgets/**` com método, `/budgets/*/installments` em `GET`, `/patients/*/financial`) e a ordem das regras.
12. **`Persistable` e `@Immutable`:** confirmar que `BudgetItem`, `BudgetDocument` e `Payment` fazem `persist` sem `SELECT` prévio e que nenhum `UPDATE` é emitido.
13. **`TransactionTemplate` com timeout** e `DocumentSlots`: confirmar que o `release()` acontece também quando o commit falha e quando a requisição é cancelada.
14. **Bucket real:** limite de 10 MB e MIME permitidos (B5/B6) e a configuração do prefixo `budgets/` seguem pendência de segurança do Supabase; o PDF tem o nome do paciente.

---

## 10. Divergências e lacunas em relação aos documentos anteriores

| # | Item | Documento anterior | Esta spec |
|---|---|---|---|
| D1 | `procedureTypeId` no `PUT /appointments/{id}` | PRD US-P3-02: "sem o campo mantém; `null` explícito remove" | **Substitui**: ausente ou `null` remove, e o cliente reenvia o atual para manter (igual a `notes` e ao `appointmentId` da nota). Evita o estado de três valores no JSON de um record. **A confirmar com o usuário**; se preferir o PRD, entra um tipo `PatchField<UUID>` com desserializador próprio |
| D2 | `ObjectKeySource` por módulo | PRD seção 2: interface em `patient` implementada em `record` **e** `commercial` | `patient` já depende de `record` (`PatientFileService`); a SPI `AnonymizationContributor` é implementada **só** por `commercial` e `record` continua chamado direto, como hoje. Menos mudança, mesmo efeito |
| D3 | Aprovação e plano | Banco 6.3: "na mesma transação ou depois" | Duas chamadas (`approve`, depois `POST …/installments`); orçamento aprovado sem parcelas é válido |
| D4 | Storage dentro da transação (H1) | `FileStorage`: "nunca dentro de uma transação"; `PatientFileService`: pool de 10 | Permitido **só** no envio e na nova revisão, com `documentStorage`, semáforo, limite por usuário e teto de 20 s; o contrato do `FileStorage` ganha esta exceção documentada |
| D5 | Tratamento de `TransactionSystemException` | Handler atual só trata `DataAccessException`/`PersistenceException` | Handler novo (único caminho do `CR011`) |
| D6 | Cancelamento isolado de parcela | Banco: `CANCELED` permitido de `OPEN` com líquido zero | Sem rota própria; só ao refazer o plano (a soma exige substituir) |
| D7 | Texto decimal para dinheiro | PRD: padrão adotado | Validado por regex e `Money`; número JSON é recusado |
| D8 | Revisão impressa no PDF | Banco: `revision` pelo trigger | O backend calcula `max + 1` sob a trava do orçamento só para imprimir; o banco confirma o mesmo número |
| D9 | Teto de parcelas e dos itens | Banco S8: `CHECK` + unicidade | Mesmos limites validados antes do banco (400), sem depender do `23514` |
| D10 | `AGENTS.md` | Vazio | Segue vazio; premissas lidas dos PRDs e specs |
| D11 | Pré-checagem de conflito por `&&` (Fase 2, spec 4.6) | Pendência da função `banco` | Não usada; a fase não depende dela |

---

## 11. Fatiamento para o `breakdown` (referência)

| Feature | Conteúdo | Depende de |
|---|---|---|
| F01 | Plataforma da fase: dependência OpenPDF, `Money`, `StorageKeys` com `budgets/`, `documentStorage` e `crm.documents.*`, `ClinicIdentity`, `AnonymizationContributor`, tradução de `CR006`–`CR011`/`23505`/`TransactionSystemException`, `no-store`, regras de `SecurityConfig`, `Phase3ItSupport` (V001–V029), `Phase3ArchitectureTest`, `ddl-auto=validate` das entidades | Fases 0 a 2 |
| F02 | Catálogo de procedimentos e procedimento na agenda (`catalog` + `scheduling`, D1) | F01 |
| F03 | Orçamento em rascunho: criar, editar (substituição de itens), consultar, descartar, `BudgetDraftValidator`, numeração | F01, F02 |
| F04 | Envio e PDF: `BudgetPdfRenderer`, fluxo atômico H1, revisões, download, `DocumentSlots`, limite por usuário | F03 |
| F05 | Decisão e plano de parcelas: `approve`, `reject`, `InstallmentPlanner`, gerar e refazer plano, ajustar vencimento | F04 |
| F06 | Pagamentos, estornos e consultas do financeiro (`receivables`, `overdue`, `cash-flow`, `revenue`, ficha do paciente) | F05 |
| F07 | Integração com o núcleo clínico: anonimização com `CR006` e PDFs, auditoria das quatro tabelas | F04, F05 |
| F08 | Verificação da fase: matriz de papéis, isolamento com filtro desligado, papel forjado, invariantes, concorrência, atomicidade do envio, pool, desempenho, higiene de log, rastreabilidade | F01–F07 |

Ordem: F01 → F02 → F03 → F04 → F05 → F06 → F07 → F08.
