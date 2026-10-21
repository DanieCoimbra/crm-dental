# Contract — F04 Exames e armazenamento

## 1. Resumo
- **Feature:** F04-patient-files
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (anexos e galeria de exames); F05 (interface interna `PatientFileService`). Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`.

| Método | Rota | Papéis | Corpo / query |
|---|---|---|---|
| POST | `/patients/{id}/files` | D, R | `multipart/form-data`: `file` (binário, 1 B – 10 MB) e `kind` (`RADIOGRAPH` \| `INTRAORAL_PHOTO` \| `OTHER`) |
| GET | `/patients/{id}/files` | D | `?page=0&size=20 (1–50)` |
| GET | `/patients/{id}/files/{fileId}/download-url` | D | — |
| DELETE | `/patients/{id}/files/{fileId}` | D | — |

Tipos aceitos (conferidos pelos bytes iniciais): `image/jpeg`, `image/png`, `image/webp`, `application/pdf`. `{id}`, `{fileId}` são uuid. Nenhum campo aceita `clinicId`, `storageKey`, `createdBy` ou `contentType`.

## 3. Saídas

### Sucesso
- **Upload** (201), para D **e** R: `{ "id": "uuid" }` (somente o id).
- **FileResponse** (itens da lista): `{ "id": "uuid", "kind": "string", "originalFilename": "string", "contentType": "string", "sizeBytes": 0, "createdAt": "instant" }` (sem `storageKey`).
- **Página:** `{ "content": [FileResponse], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }`, ativos, mais recentes primeiro.
- **URL assinada** (200, `Cache-Control: no-store`): `{ "url": "https://…/storage/v1/object/sign/<bucket>/<chave>?token=…", "expiresIn": 60 }`.
- **Remoção:** 204.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | Arquivo vazio; tipo não permitido ou diferente do declarado; `kind` inválido |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R em lista, download-url ou remoção |
| 404 `not-found` | Paciente ou exame inexistente, removido, de outro paciente ou de outra clínica |
| 409 `conflict` | Paciente anonimizado |
| 413 | Arquivo acima de 10 MB |
| 429 `rate-limited` | 11º upload no mesmo minuto (com `Retry-After`) |
| 503 `unavailable` | Storage indisponível (upload ou assinatura) |

## 4. Regras e limites
- Máximo 10 MB por arquivo; 10 uploads por minuto por usuário.
- Chave do objeto: `<clinic_id>/<patient_id>/<uuid>` gerada pelo servidor; o nome do arquivo não entra nela.
- Nome original sanitizado (último segmento, sem controle, NFC, ≤ 255 caracteres).
- A recepção só anexa; não lista, baixa, altera nem remove o que anexou.
- Remoção é lógica; o objeto permanece no Storage até a anonimização.
- URL assinada: 60 s, só após papel D, tenant e prefixo da chave; nunca bucket público.
- O Storage não é chamado dentro de transação de banco.

### Interfaces internas
```java
// crm_data.record.service.PatientFileService (para patient/F05)
List<String> storageKeysOf(UUID patientId);          // todas, inclusive removidas logicamente
void purgeObjects(Collection<String> keys);          // lança StorageException se falhar

// crm_data.shared.storage.FileStorage
void save(String key, InputStream content, String contentType, long size) throws IOException;
InputStream load(String key) throws IOException;     // sem uso nesta fase
void delete(String key) throws IOException;
void deleteAll(Collection<String> keys) throws IOException;
URI createSignedUrl(String key, Duration ttl) throws IOException;
```

## 5. Eventos emitidos
| Evento | Gatilho | Payload |
|---|---|---|
| `File.CompensationFailed` (log interno) | Compensação do upload falhou | `{patientId, fileId}` |

## 6. Critérios de aceite (integração)
- [ ] D e R recebem 201 `{id}` no upload; R recebe 403 em lista, download-url e remoção.
- [ ] Upload de R não executa `SELECT` nem `RETURNING` em `patient_files` (inspeção de SQL).
- [ ] JPEG declarado com bytes de PNG → 400; arquivo de 10 MB + 1 → 413; arquivo vazio → 400.
- [ ] Falha do `INSERT` (paciente anonimizado) → objeto enviado é apagado (0 órfãos).
- [ ] 11º upload no minuto → 429 com `Retry-After`.
- [ ] `download-url` de exame removido ou de outra clínica → 404; URL expira em 60 s e não aparece em log.
- [ ] `DELETE` preenche `deleted_at` e o objeto permanece no Storage.
- [ ] Em `prod`, a aplicação não sobe sem as três variáveis `CRM_STORAGE_*`.
- [ ] Nenhuma resposta contém `storageKey`.
