# Contract — F03 Anulação de documentos clínicos

## 1. Resumo
- **Feature:** F03-clinical-document-void
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (anular documento), F04 (marca "ANULADO" no PDF), F06. Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`.

| Método | Rota | Papéis | Corpo |
|---|---|---|---|
| POST | `/clinical-documents/{id}/void` | D | `{ "version": long, "reason": "≤200 caracteres"? }` |

`voidedAt`, `voidedBy`, `content`, `number`, `clinicId` e qualquer outro campo do corpo são ignorados.

## 3. Saídas

### Sucesso
- **200:** `DocumentResponse` da F02 com `state: "VOIDED"`, `voidedAt`, `voidedByName`, `voidReason` (ou `null`) e `version` incrementada; o conteúdo continua em claro.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | `version` ausente; `reason` com mais de 200 caracteres |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R |
| 404 `not-found` | Documento inexistente ou de outra clínica |
| 409 `conflict` | Já anulado ou anonimizado; `version` defasada; anulação concorrente |
| 500 `integrity` | `sha256` divergente ao montar a resposta (a anulação é desfeita) |

## 4. Regras e limites
- Só se anula **uma vez**; não há reativação, edição nem exclusão.
- A anulação não altera conteúdo, número, data de emissão nem `sha256`.
- `reason`: `strip`; vazio → `null`; ≤ 200 caracteres; nunca em log.
- Documento anonimizado não pode ser anulado.

### Interfaces internas
```java
// crm_data.document.domain.ClinicalDocument
void markVoided(Instant now, UUID by, String reason);   // única mutação permitida

// crm_data.document.service.ClinicalDocumentService
DocumentResponse voidDocument(UUID id, DocumentRequests.Void request);
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Document.Voided` | Após o commit da anulação | `{documentId, patientId, userId}` |

## 6. Critérios de aceite (integração)
- [ ] D anula (200, `VOIDED`); R recebe 403; sem token, 401; documento de outra clínica → 404.
- [ ] Segunda anulação → 409; `version` velha → 409; `reason` de 201 caracteres → 400.
- [ ] Duas anulações simultâneas (20 repetições): uma 200 e uma 409, uma única linha `VOID` na auditoria.
- [ ] O SQL emitido atualiza somente `voided_at`, `voided_by`, `void_reason` e `version`.
- [ ] `GET /audit-log?tableName=clinical_documents&recordId=<id>` traz `INSERT` e `VOID` com o usuário correto.
- [ ] Anular documento de paciente anonimizado → 409; corpo com `content`/`voidedAt` forjados não altera nada.
- [ ] Nenhum log contém o `reason`; falha de integridade desfaz a anulação.
