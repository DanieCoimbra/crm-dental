# PRD — F03 Anulação de documentos clínicos

**Derivação:** PRD global da Fase 4, US-P4-06; FR-7; decisões I3 e I6 (documento imutável, só se anula). Spec global, seções 4.3, 5, 6 e 7.
**Dependências externas:** F02 (`ClinicalDocument`, `DocumentState`, `DocumentResponse`, repositório), F01 (`ConflictMessages.DOCUMENT_STATE`, `AuditAction.VOID`), banco V032/V033 (trigger de imutabilidade e `write_document_audit`). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** documento emitido com erro não pode ser editado nem apagado, mas precisa ser invalidado de forma rastreável.
- **Solução:** `POST /clinical-documents/{id}/void` marca `voided_at`, `voided_by` e `void_reason` uma única vez, com controle otimista e auditoria `VOID` gravada pelo banco.
- **Metas:**
  1. Duas anulações simultâneas: exatamente uma 200 e uma 409, e uma linha `VOID` na auditoria.
  2. O `UPDATE` toca somente `voided_at`, `voided_by`, `void_reason`, `version` (provado por log SQL no IT).
  3. Anulação p95 ≤ 100 ms.
  4. Cobertura ≥ 80% no ramo de anulação do `ClinicalDocumentService`.

## 2. User stories

### US-001: Anular documento
**Descrição:** Como dentista, quero anular um documento emitido por engano para que ele deixe de valer sem sumir do histórico.

**Critérios de aceite:**
- [ ] `POST /api/v1/clinical-documents/{id}/void` (só D) com `{version, reason?}`; `reason` ≤ 200 caracteres, `strip`, vazio vira nulo.
- [ ] Devolve 200 com o `DocumentResponse`: `state: VOIDED`, `voidedAt`, `voidedByName`, `voidReason`, nova `version`; o conteúdo continua legível.
- [ ] Documento já anulado ou anonimizado → 409 (`DOCUMENT_STATE`); `version` defasada → 409 (`STALE_VERSION`).
- [ ] Documento de outra clínica ou inexistente → 404; recepção → 403.
- [ ] A auditoria mostra uma linha `VOID` por anulação em `GET /audit-log?tableName=clinical_documents&recordId=...`.

### US-002: Anulação não reabre nem altera
**Descrição:** Como responsável pela clínica, quero garantia de que um documento anulado não volta a valer.

**Critérios de aceite:**
- [ ] Não existe rota de "desanular" nem de edição; tentar `PUT`/`PATCH`/`DELETE` → 405/404 conforme o roteamento atual.
- [ ] Mesmo se o corpo trouxer `voidedAt`, `content` ou `number`, nada disso é aplicado.

## 3. Requisitos funcionais

- **FR-1:** `ClinicalDocumentService.voidDocument(id, request)` em `@Transactional` com `@PreAuthorize`.
- **FR-2:** `ClinicalDocument.void(now, by, reason)` mantendo as demais colunas `updatable=false` (`@DynamicUpdate`).
- **FR-3:** `OptimisticLockingFailureException` e a disputa de duas transações traduzidas para 409.
- **FR-4:** `DocumentRequests.Void(version, reason)` validado; `Clock` injetado.
- **FR-5:** Evento `Document.Voided` com ids apenas; `reason` nunca em log.

## 4. Fora do escopo

- Reemissão automática do documento substituto; correção por edição.
- Anulação em lote e anulação por recepção.
- Notificação ao paciente.

## 5. Considerações técnicas e riscos

- O trigger do banco recusa qualquer outra mutação; o backend não deve tentar (testado por inspeção do SQL emitido).
- A anulação concorrente perde por versão no `UPDATE ... WHERE version = ?`, sem chegar ao trigger.
- O motivo é texto livre em claro (≤ 200 caracteres) e pode conter dado de saúde: fica fora de log, de exceção e de evento.
