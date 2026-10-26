# Contract — F03 Orçamento em rascunho

## 1. Resumo
- **Feature:** F03-budget-drafts
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (montagem e consulta de orçamentos), F04 (envio), F05 (decisão e parcelas) e `finance` via `BudgetLookup`. Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`. Valores monetários em texto decimal; datas `AAAA-MM-DD`.

| Método | Rota | Papéis | Corpo / query |
|---|---|---|---|
| POST | `/budgets` | D | `{ "patientId": uuid, "appointmentId": uuid?, "validUntil": date?, "notes": "≤500"?, "discountAmount": "0..999999.99"?, "items": [ { "procedureTypeId": uuid, "quantity": 1..999, "discountAmount": "…"? } ] (1..50) }` |
| PUT | `/budgets/{id}` | D | `{ "appointmentId", "validUntil", "notes", "discountAmount", "items": [...], "version": long }` — substitui os itens; só `DRAFT` |
| POST | `/budgets/{id}/discard` | D | `{ "version": long }` |
| GET | `/budgets` | D, R | `?status=… (repetível) &patientId=uuid &number=int≥1 &page=0.. &size=1..50 (padrão 20)` |
| GET | `/budgets/{id}` | D, R | — |
| GET | `/patients/{id}/budgets` | D, R | `?page&size` (`size` ≤ 50) |

`clinicId`, `createdBy`, `id`, `status`, `number`, `totalAmount`, `unitPrice`, `description`, `lineTotal`, `sentAt`, `decidedAt` e `patientId` (no `PUT`) do corpo são ignorados. `status` ∈ `DRAFT`, `SENT`, `APPROVED`, `REJECTED`.

## 3. Saídas

### Sucesso
- **Orçamento** (201 com `Location: /api/v1/budgets/{id}` na criação; 200 nas demais; `Cache-Control: no-store`):
  ```json
  { "id": "uuid", "number": 12, "patient": { "id": "uuid", "name": "string" }, "appointmentId": "uuid|null",
    "status": "DRAFT", "subtotalAmount": "850.00", "discountAmount": "50.00", "totalAmount": "800.00",
    "validUntil": "date|null", "notes": "string|null", "sentAt": "instant|null", "decidedAt": "instant|null",
    "createdBy": "uuid", "createdAt": "instant", "updatedAt": "instant", "version": 0,
    "items": [ { "id": "uuid", "position": 1, "procedureTypeId": "uuid", "description": "Restauração",
                 "quantity": 2, "unitPrice": "250.00", "discountAmount": "0.00", "lineTotal": "500.00" } ],
    "documents": [ { "id": "uuid", "revision": 1, "generatedAt": "instant", "sizeBytes": 48211 } ] }
  ```
- **Lista** (200): `{ "content": [BudgetSummaryResponse], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }`, onde cada item traz `id`, `number`, `patient {id, name}`, `status`, `totalAmount`, `validUntil`, `sentAt`, `decidedAt`, `createdAt`, `version`.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | Itens fora de 1–50, quantidade fora de 1–999, dinheiro mal formado ou acima do teto, desconto de item maior que a linha, desconto do orçamento maior que a soma, total acima do teto, `notes` > 500, `validUntil` < 2000-01-01, `status`/`number`/`page`/`size` inválidos |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R em `POST`/`PUT`/`discard` |
| 404 `not-found` | Paciente, procedimento, atendimento ou orçamento inexistente / de outra clínica |
| 409 `conflict` | Paciente anonimizado ou inativo; atendimento de outro paciente; `PUT`/`discard` fora de `DRAFT` (com o status atual); `version` defasada |
| 422 `business-rule` | `reason: PROCEDURE_INACTIVE` ou `AMOUNT_OUT_OF_RANGE` |

## 4. Regras e limites
- Máximo de 50 itens, quantidade 1–999, R$ 999.999,99 por campo e no total; desconto em **valor**, nos dois níveis.
- O número do orçamento é sequencial por clínica, atribuído pelo banco; o total é `Σ lineTotal − discountAmount`, calculado pelo banco; `unitPrice` e `description` são copiados do procedimento no momento do item (snapshot) e depois imutáveis.
- Só `DRAFT` aceita edição; o `PUT` substitui os itens inteiros (reordenar é reenviar a lista). O `version` válido é o da **resposta** mais recente.
- A recepção só lê (inclui o total e os itens); nunca vê parcelas.
- `notes` é texto claro, vai ao PDF e **não leva dado clínico**.

### Interfaces internas
```java
// crm_data.commercial.service.BudgetLookup (criada aqui; ampliada pelas F05 e F06)
Map<UUID, BudgetBrief> brief(Collection<UUID> budgetIds);   // record BudgetBrief(UUID id, int number, UUID patientId)
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Budget.Created` | Após o commit da criação | `{budgetId, patientId, userId}` |
| `Budget.Updated` | Após editar o rascunho | `{budgetId, userId}` |
| `Budget.Discarded` | Após descartar | `{budgetId, userId}` |

## 6. Critérios de aceite (integração)
- [ ] D cria (201), edita e descarta; R recebe 403 nas três e 200 nas leituras; sem token, 401.
- [ ] 20 repetições de dois `POST /budgets` simultâneos na mesma clínica: números distintos e sequenciais, nenhum 500.
- [ ] `number`, `totalAmount`, `unitPrice` e `description` enviados pelo cliente não surtem efeito; a resposta traz os valores do banco.
- [ ] `PUT` substitui os itens, reordena e mantém o total correto; desconto anterior alto não estoura o total intermediário.
- [ ] `PUT` e `discard` em `SENT`/`APPROVED`/`REJECTED` → 409 com o status atual.
- [ ] Paciente anonimizado/inativo → 409; procedimento inativo → 422; atendimento de outro paciente → 409.
- [ ] `GET /budgets` filtra por `status` (repetido), `patientId` e `number`; página fora de 1–50 → 400.
- [ ] Nenhuma resposta traz `clinicId` nem `storageKey`; nenhum valor, nome ou `notes` em log; nenhuma linha de outra clínica (filtro desligado); `ddl-auto=validate` passa com `Budget` e `BudgetItem`.
