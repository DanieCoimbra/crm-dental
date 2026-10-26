# Spec — F04 Envio do orçamento e PDF

Complementa `docs/global/backend/Fase-3/spec.md` (seções 2.2, 4.5, 4.6, 4.7, 5.1, 6.1, 6.2, 7 e 9).

## 1. Visão técnica
- **Feature:** F04 Envio e PDF.
- **Stack:** OpenPDF (`com.lowagie.text`), `MessageDigest` SHA-256, `TransactionTemplate`, `RestClient` (Supabase Storage via `documentStorage`), Bucket4j (limite por usuário), Spring Web MVC (interceptor).
- **Abordagem:** `BudgetDocumentService` em `crm_data.commercial.service`, sem `@Transactional` de método; a janela da transação é controlada por `TransactionTemplate` para coordenar banco, Storage e compensação. Sem migrations: usa V025.

## 2. Dados
- **Tabela:** `core.budget_documents` (`ux_bdoc_budget_revision`, `ux_bdoc_storage_key`, `ix_bdoc_budget_active`). Nenhuma alteração de schema.
- **`BudgetDocument`** (`TenantAwareEntity`, `@Immutable`, `Persistable<UUID>`): `budgetId`, `patientId`, `revision` (`insertable = false`), `storageKey`, `contentType`, `sizeBytes`, `sha256` (`varchar`), `generatedAt` (`insertable = false`), `deletedAt` (somente leitura), `createdBy`, `createdAt`.
- **Repositório:** `findActiveByBudgetId` (`deletedAt IS NULL`, `revision DESC`), `findByIdAndBudgetIdAndDeletedAtIsNull`, `existsByStorageKey`, `maxRevisionByBudgetId` (inclui removidas).
- **Estado em memória:** `DocumentSlots` (`Semaphore(maxConcurrent)`), `DocumentRateLimiter` (Bucket4j por usuário, padrão do `UploadRateLimiter`).

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `BudgetDocumentController` | `POST /budgets/{id}/send`, `POST /budgets/{id}/documents`, `GET /budgets/{id}/documents`, `GET /budgets/{id}/documents/{docId}/download-url` |
| `BudgetDocumentService` | Fluxo H1 (`send`, `regenerate`), listagem e URL assinada |
| `BudgetPdfRenderer` / `BudgetPdfModel` | PDF em memória a partir de um modelo imutável; puro |
| `DocumentSlots` | `tryAcquire(2 s)` / `release()`; sem vaga → 503 + `Retry-After: 5` |
| `DocumentRateLimiter` + `DocumentRateLimitInterceptor` + `CommercialWebConfig` | 6 por minuto por usuário em `POST ^/api/v1/budgets/[^/]+/(send|documents)$` → 429 |
| `ClinicIdentity` / `PatientLookup.briefs` | Cabeçalho da clínica e nome do paciente |
| `BudgetTransitions` (F03) | `SEND` (`DRAFT → SENT`) e `REGENERATE_DOCUMENT` |
| `documentStorage` (F01) | Gravação do objeto com timeout de 10 s |
| `DocumentResponse` | `id`, `revision`, `generatedAt`, `sizeBytes`; sem `storageKey` |

## 4. Lógica
**`send(budgetId, version)`:** (1) `DocumentSlots.tryAcquire`; (2) `TransactionTemplate` (timeout 20 s): carregar com trava, validar `version` e a transição, exigir ≥ 1 item e total > 0, `status = SENT`, `flush`, `reload`; ler itens, paciente e `ClinicIdentity`; montar `BudgetPdfModel` e renderizar; `size` e `sha256`; > 5 MB → 422; **registrar a chave num `AtomicReference` antes de gravar**; `documentStorage.save(key, bytes, "application/pdf", size)`; `persist(BudgetDocument)` + `flush` + `reload`; devolver o orçamento com o documento; (3) commit; (4) `finally`: `release()`; evento `Budget.Sent`.

**Compensação:** em qualquer `RuntimeException` com a chave registrada — se o corpo terminou e a falha veio do commit, conferir em transação própria de leitura se existe `BudgetDocument` com a `storage_key`: existe → não apagar; não existe → `documentStorage.delete(key)`. Falha ao apagar: evento `StorageCompensationFailed(budgetId)` e `log.error` com ids; a resposta continua sendo a do erro original.

**`regenerate(budgetId)`:** mesmo fluxo sem mudar status: carregar com trava (serializa revisões concorrentes), exigir `SENT`/`APPROVED`/`REJECTED`, `revision = maxRevision + 1` só para imprimir, gravar, devolver 201 `DocumentResponse` (a revisão real vem do `reload`).

**Download:** transação de **leitura** curta para obter a `storage_key` do documento ativo (`id`, `budgetId`); fora dela, `createSignedUrl(key, ttl)` com o TTL de `CrmProperties.Storage` (60 s); `StorageKeys.belongsTo` conferido; `StorageException` → 503.

**PDF:** A4, margens de 40 pt, `BaseFont.CP1252` (Helvetica); cabeçalho, título, paciente, tabela de 5 colunas, totais, validade, observações e rodapé "Página x de y"; `NumberFormat` pt-BR para `R$` e `dd/MM/aaaa`; metadados só com título e autor genérico.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `send` sem itens ou total zero | 409 "Inclua ao menos um item com valor maior que zero." |
| `send`/`regenerate` fora do status permitido | 409 com o status atual (`CR008`/`CR007` são a rede) |
| Falha do Storage ao gravar | `ROLLBACK`, `DRAFT`, objeto apagado, 503 |
| Falha no `INSERT` de `budget_documents` | `ROLLBACK`, objeto apagado, 500 |
| Commit com resultado incerto | Conferir `budget_documents` pela `storage_key`; só apagar se não houver linha |
| Falha na compensação | Evento `StorageCompensationFailed`; resposta original mantida |
| PDF > 5 MB | 422 `PDF_TOO_LARGE`; nada gravado |
| Sem vaga em `DocumentSlots` | 503 + `Retry-After: 5` |
| 7º envio por minuto do usuário | 429 |
| Duas revisões simultâneas | A trava serializa; revisões distintas e sequenciais |
| Download de documento removido / de outra clínica | 404 |
| Recepção em `send`/`documents` (POST) | 403 antes do banco |

## 6. Segurança e desempenho
- Escrita só D; leituras D e R; conteúdo gerado só pelo servidor; sem CPF no PDF; `storageKey` nunca na resposta; URL assinada nunca em log.
- Log só com `budgetId`, `patientId`, `userId`, `revision`, tamanho e contagens; nunca nome, valor, chave ou URL.
- Envio p95 ≤ 1,5 s (Storage local); geração do PDF ≤ 300 ms; no pior caso 3 conexões retidas por ~15 s; leitura concorrente p95 ≤ 200 ms.
