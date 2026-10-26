# Contract — F04 Envio do orçamento e PDF

## 1. Resumo
- **Feature:** F04-budget-send-and-pdf
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (enviar, baixar e reemitir o PDF), F05 (aprovação exige `SENT`) e F07 (anonimização dos PDFs). Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`.

| Método | Rota | Papéis | Corpo |
|---|---|---|---|
| POST | `/budgets/{id}/send` | D | `{ "version": long }` |
| POST | `/budgets/{id}/documents` | D | sem corpo (nova revisão) |
| GET | `/budgets/{id}/documents` | D, R | — |
| GET | `/budgets/{id}/documents/{docId}/download-url` | D, R | — |

`clinicId`, `storageKey`, `revision`, `sha256` e `sizeBytes` do corpo são ignorados (nada disso é entrada).

## 3. Saídas

### Sucesso
- **`send`** (200): orçamento completo da F03 com `status = SENT`, `sentAt` preenchido e `documents: [ { "id", "revision": 1, … } ]`.
- **`POST /documents`** (201, `Location`): 
  ```json
  { "id": "uuid", "revision": 2, "generatedAt": "instant", "sizeBytes": 48211 }
  ```
- **Lista** (200): `[DocumentResponse]` das revisões ativas, maior revisão primeiro. **Nunca** `storageKey`.
- **URL assinada** (200, `no-store`): `{ "url": "https://…", "expiresInSeconds": 60 }`.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R em `send` ou `POST /documents` |
| 404 `not-found` | Orçamento ou documento inexistente, removido ou de outra clínica |
| 409 `conflict` | Sem itens ou total zero; status fora do permitido (com o status atual); `version` defasada |
| 422 `business-rule` | `reason: PDF_TOO_LARGE` |
| 429 `rate-limited` | Mais de 6 envios/revisões por minuto por usuário (`Retry-After`) |
| 503 `unavailable` | Storage indisponível ou lento; sem vaga de envio (`Retry-After: 5`) |
| 500 `internal` | Falha ao gravar o documento (sem detalhes) |

## 4. Regras e limites
- O envio é **atômico**: ou o orçamento fica `SENT` com o PDF registrado, ou continua `DRAFT` sem PDF e sem objeto órfão.
- O PDF reflete exatamente o orçamento congelado; reemitir gera **nova revisão** e conserva as anteriores; revisão ativa = a de maior número.
- Limites: PDF ≤ 5 MB; 6 envios/revisões por minuto por usuário; 3 envios simultâneos por instância; upload com timeout de 10 s; URL de download válida por 60 s.
- O PDF traz o nome do paciente, **sem CPF**; o objeto fica em `<clinic>/<patient>/budgets/<uuid>` no bucket privado; só se acessa por URL assinada gerada depois de checar tenant.
- `send` não envia e-mail nem WhatsApp.

### Interfaces internas
```java
// crm_data.commercial.service.BudgetPdfRenderer (puro)
byte[] render(BudgetPdfModel model);   // sem banco, Storage ou relógio

// crm_data.commercial.service.BudgetPdfModel (record imutável)
// clinicName, clinicAddress, clinicPhone, patientName, number, revision, sentOn, issuedOn,
// validUntil, items[(description, quantity, unitPrice, discount, lineTotal)], subtotal, discount, total, notes
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Budget.Sent` | Após o commit do envio | `{budgetId, patientId, revision, userId}` |
| `Budget.DocumentGenerated` | Após o commit de uma nova revisão | `{budgetId, revision, userId}` |
| `Budget.StorageCompensationFailed` | Falha ao apagar o objeto na compensação | `{budgetId}` |

## 6. Critérios de aceite (integração)
- [ ] D envia (200); R recebe 403; R baixa o PDF (200); sem token, 401.
- [ ] Falha injetada em `save`, em `delete`, no `INSERT` e no commit: orçamento `DRAFT`, sem linha, sem objeto órfão (ou evento de compensação).
- [ ] O PDF extraído contém número, itens, total e observações e **não** contém CPF; acentos corretos; 50 itens cabem.
- [ ] `sha256` e `size_bytes` conferem com o objeto lido do `FileStorage` de teste.
- [ ] 20 repetições de duas revisões simultâneas: revisões distintas e sequenciais, nenhum 500.
- [ ] 7º envio no minuto → 429; sem vaga → 503 com `Retry-After`; o semáforo é liberado também em falha.
- [ ] Download de documento removido/de outra clínica → 404; a resposta nunca traz `storageKey`; nenhuma URL, chave ou nome em log.
- [ ] Com 3 envios lentos simulados, uma leitura de orçamento mantém p95 ≤ 200 ms; `ddl-auto=validate` passa com `BudgetDocument`.
