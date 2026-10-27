# Spec — F03 Anulação de documentos clínicos

## 1. Technical Overview
- **Feature:** `POST /clinical-documents/{id}/void`.
- **Tech Stack:** Spring Web MVC, Spring Data JPA (`@Version`, `@DynamicUpdate`), Bean Validation.
- **Architecture Approach:** acréscimo ao `ClinicalDocumentService` e ao `ClinicalDocumentItemController` da F02; regra de estado na entidade; auditoria pelo trigger do banco.

## 2. Data Models & Schema
- **Banco:** nenhuma mudança. Colunas atualizáveis: `voided_at`, `voided_by`, `void_reason`, `version` (grant de coluna do `crm_app`).
- **Entidade:** `ClinicalDocument.void(Instant now, UUID by, String reason)` define os três campos; demais atributos `updatable=false`.
- **DTO:** `DocumentRequests.Void(@NotNull Long version, @Size(max=200) String reason)`.
- Estado em memória: nenhum.

## 3. Component Architecture
- `ClinicalDocumentService.voidDocument(UUID id, Void request)`.
- `ClinicalDocumentItemController`: `POST /clinical-documents/{id}/void`.
- `ConflictMessages`: `DOCUMENT_STATE` ("Documento já anulado ou anonimizado."), `STALE_VERSION` (existente).
- `DocumentEvents.VOIDED(documentId, patientId, userId)` e log INFO sem texto.

## 4. Core Logic & Algorithms
1. `reason = strip(reason)`; vazio → `null`; > 200 caracteres → 400.
2. `@Transactional`: `findById` (tenant filter) → 404.
3. `anonymizedAt != null || voidedAt != null` → 409 `DOCUMENT_STATE`.
4. `request.version != doc.version` → 409 `STALE_VERSION`.
5. `doc.void(clock.instant(), TenantContext.userId(), reason)`; `saveAndFlush`.
6. `UPDATE` emitido só com `voided_at`, `voided_by`, `void_reason`, `version`; trigger do banco grava `VOID` na auditoria.
7. Decifra o conteúdo (após `verify`) e devolve o `DocumentResponse` com `state: VOIDED`.
8. Corrida: a transação perdedora recebe `ObjectOptimisticLockingFailureException` (0 linhas atualizadas) → 409; nenhum erro do trigger chega ao cliente.

## 5. Error Handling & Edge Cases
- `reason` > 200 caracteres ou `version` ausente → 400 `validation`.
- Já anulado/anonimizado → 409; versão velha → 409.
- Documento de outra clínica → 404 (filtro de tenant); recepção → 403.
- `P0001`/`23514` do trigger (não deve ocorrer) → 500.
- Falha de `verify` ao montar a resposta → 500 `integrity` e **rollback** da anulação.

## 6. Security & Performance
- Papel só do token; `voidedBy` vem de `TenantContext.userId()`, nunca do corpo.
- `reason` fora de log, evento e mensagens de exceção.
- Meta: p95 ≤ 100 ms; uma consulta de leitura e um `UPDATE`.
