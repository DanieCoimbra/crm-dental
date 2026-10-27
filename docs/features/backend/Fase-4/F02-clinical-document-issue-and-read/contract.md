# Contract — F02 Emissão e leitura de documentos clínicos

## 1. Resumo
- **Feature:** F02-clinical-document-issue-and-read
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (emitir, listar e abrir documentos), F03 (anular), F04 (PDF), F06. Requer `Authorization: Bearer` da Fase 0 e CRO cadastrado (F01).

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`.

| Método | Rota | Papéis | Corpo / query |
|---|---|---|---|
| POST | `/patients/{id}/clinical-documents` | D | `{ "documentType": "CERTIFICATE"\|"PRESCRIPTION"\|"REFERRAL", "issuedOn": "yyyy-MM-dd"?, "restDays": 1..90?, "targetSpecialty": "2..60"?, "content": "≤4000"?, "appointmentId": "uuid"? }` |
| GET | `/patients/{id}/clinical-documents` | D | `?page=0&size=20(1..50)&type=` |
| GET | `/clinical-documents/{id}` | D | — |

Regras por tipo: `CERTIFICATE` exige `restDays` (`content` opcional, sem `targetSpecialty`); `PRESCRIPTION` exige `content` (sem `restDays`/`targetSpecialty`); `REFERRAL` exige `targetSpecialty` e `content` (sem `restDays`). `clinicId`, `number`, `sha256`, `voided*`, `anonymizedAt`, `createdBy` e `version` do corpo são ignorados.

## 3. Saídas

### Sucesso
- **Emitir** (201, `Location: /api/v1/clinical-documents/{id}`): `DocumentResponse`
  ```json
  { "id": "uuid", "patientId": "uuid", "appointmentId": null, "documentType": "CERTIFICATE", "number": 12,
    "issuedOn": "2026-10-27", "restDays": 2, "targetSpecialty": null, "content": "texto em claro",
    "state": "ACTIVE", "anonymized": false, "voidedAt": null, "voidedByName": null, "voidReason": null,
    "createdAt": "instant", "createdByName": "Dra. Ana", "version": 0 }
  ```
- **Detalhe** (200): `DocumentResponse`; se `ANONYMIZED`, `content`, `restDays`, `targetSpecialty` e `voidReason` são `null`.
- **Lista** (200): página de `DocumentSummaryResponse` (mesmos campos, **sem** `content`, `voidReason` e `version`), ordem `createdAt DESC, id DESC`.
- **Nunca** saem `clinicId`, `sha256`, `createdBy` (id) nem o texto cifrado.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | Tipo desconhecido; campo proibido/obrigatório por tipo; texto vazio ou acima do limite; `issuedOn` < 2000-01-01; `size` fora de 1 a 50 |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R em qualquer rota |
| 404 `not-found` | Paciente, documento ou consulta inexistente ou de outra clínica |
| 409 `conflict` | CRO ausente (`PROFESSIONAL_REGISTRY_MISSING`); paciente inativo ou anonimizado |
| 422 `business-rule` | `ISSUED_ON_IN_FUTURE`; `APPOINTMENT_OF_OTHER_PATIENT` |
| 429 `rate-limited` | Mais de 30 emissões por minuto por usuário (`Retry-After`) |
| 500 `integrity` | `sha256` divergente ao abrir (sem texto) |
| 500 `internal` | `42501`, `28000`, `23514` residual, falha de chave AES |

## 4. Regras e limites
- Documento é **imutável**: não há `PUT`/`PATCH`/`DELETE`.
- `issuedOn` padrão = hoje no fuso da clínica; futuro recusado.
- Numeração por clínica e tipo, atribuída pelo banco; reler após gravar.
- `content` cifrado na aplicação (AES-256-GCM); `sha256` hex minúsculo sobre `v1|TYPE|patient_id|issued_on|rest_days|target_specialty|content(cifrado)`, comparado em tempo constante.
- Paciente anonimizado: documentos permanecem (número e metadados), sem conteúdo.

### Interfaces internas
```java
// crm_data.document.service.DocumentIntegrity
String compute(DocumentType type, UUID patientId, LocalDate issuedOn, Short restDays,
               String targetSpecialty, String cipherText);
void verify(ClinicalDocument doc);          // IntegrityException se divergir

// crm_data.document.service.DocumentCipher
String encrypt(String plain); String decrypt(String cipher);

// crm_data.document.domain.ClinicalDocument
void markVoided(Instant now, UUID by, String reason);   // usado pela F03
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Document.Issued` | Após o commit da emissão | `{documentId, patientId, type, userId}` |

## 6. Critérios de aceite (integração)
- [ ] D emite os três tipos (201) e lê (200); R recebe 403 em todas as rotas; sem token, 401.
- [ ] Combinações inválidas por tipo, texto de 4001 caracteres ou 16001 bytes → 400; `issuedOn` futuro → 422.
- [ ] Sem CRO → 409; paciente inativo/anonimizado → 409; `appointmentId` de outro paciente → 422, de outra clínica → 404.
- [ ] Consulta administrativa mostra `content` cifrado (sem o texto); alterar `content` ou `sha256` no banco → detalhe 500 `integrity`.
- [ ] 20 repetições de duas emissões simultâneas do mesmo tipo: números distintos e sequenciais; tipos e clínicas diferentes não se esperam.
- [ ] Lista não traz `content`, não repete consulta por item e respeita `type` e `size`; paciente/documento de outra clínica → 404.
- [ ] Anonimizar o paciente zera o conteúdo e preserva a linha e o número; a lista continua mostrando o documento.
- [ ] 31ª emissão no minuto → 429; nenhum log contém texto, nome de paciente ou CRO (teste por mutação); `ddl-auto=validate` passa com `ClinicalDocument`.
