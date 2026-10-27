# Contract — F04 PDF do documento clínico

## 1. Resumo
- **Feature:** F04-clinical-document-pdf
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (abre o PDF em nova aba/impressão), F06. Requer `Authorization: Bearer` da Fase 0; só o dentista.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`.

| Método | Rota | Papéis | Corpo |
|---|---|---|---|
| GET | `/clinical-documents/{id}/pdf` | D | — |

## 3. Saídas

### Sucesso
- **200** (binário): `Content-Type: application/pdf`; `Content-Disposition: inline; filename="atestado-12.pdf"` (`receita-<n>.pdf`, `encaminhamento-<n>.pdf`); `Cache-Control: no-store`; `X-Content-Type-Options: nosniff`.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R |
| 404 `not-found` | Documento inexistente ou de outra clínica (inclui `P0002` do download) |
| 409 `conflict` | Documento anonimizado (`DOCUMENT_ANONYMIZED`) |
| 422 `business-rule` | `reason: PDF_TOO_LARGE` (> 4 MB), com rollback |
| 429 `rate-limited` | Mais de 30 PDFs por minuto por usuário (`Retry-After`) |
| 500 `integrity` | `sha256` divergente |
| 500 `internal` | Falha de renderização, `42501`, `28000` |

## 4. Regras e limites
- Cada PDF entregue grava **um** `DOWNLOAD`; falha em qualquer etapa antes do corpo desfaz o registro.
- O PDF nunca vai ao Storage; é gerado em memória e devolvido na resposta.
- Conteúdo: clínica, título e número, paciente (nome), data de emissão, texto por tipo, assinatura com nome e `CRO número/UF`, rodapé "<Tipo> nº n — página x de y". **Sem CPF e sem data de nascimento.**
- `VOIDED`: marca "ANULADO" em todas as páginas e "Anulado em dd/MM/aaaa" abaixo do título.
- Limites: 4 MB por PDF; 30 PDFs/min por usuário (`crm.clinical-documents.pdfs-per-minute`); texto de 4000 caracteres em ≤ 2 páginas.
- Cabeçalho e CRO são os **atuais** (sem snapshot).

### Interfaces internas
```java
// crm_data.document.service.DocumentPdfRenderer (puro)
byte[] render(DocumentPdfModel model);   // sem banco, Storage, relógio ou Spring

// crm_data.document.service.DocumentPdfModel (record imutável)
// clinicName, clinicAddress, clinicPhone, type, number, issuedOn, patientName, restDays,
// targetSpecialty, content, voidedOn, dentistName, dentistCro, dentistCroState

// crm_data.document.service.DocumentDownloadRecorder
void record(UUID documentId);            // select cast(core.record_document_download(:id) as text)
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Document.Downloaded` | Após o commit | `{documentId, userId}` |

## 6. Critérios de aceite (integração)
- [ ] D baixa o PDF dos três tipos (200, cabeçalhos exatos); R recebe 403; sem token, 401; outra clínica → 404.
- [ ] O texto extraído (`PdfReader`) contém número, frase padrão por tipo, complemento, nome do paciente, `CRO n/UF`; **não** contém CPF nem data de nascimento; acentos corretos; metadados sem nome de paciente.
- [ ] Documento anulado traz "ANULADO" em todas as páginas; anonimizado → 409.
- [ ] Cada PDF grava exatamente um `DOWNLOAD` no `GET /audit-log`; falha injetada na renderização, no limite de 4 MB e no commit: nenhuma linha `DOWNLOAD`.
- [ ] 4000 caracteres cabem em ≤ 2 páginas; recepção que chame a função do banco recebe `42501` (e o 403 sai antes).
- [ ] 31º PDF no minuto → 429; adulteração de `content` → 500 `integrity`; nenhum log contém texto, nome ou CRO.
- [ ] p95 ≤ 300 ms; com 3 PDFs simultâneos uma leitura mantém p95 estável.
