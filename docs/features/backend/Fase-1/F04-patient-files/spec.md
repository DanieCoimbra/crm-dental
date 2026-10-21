# Spec — F04 Exames e armazenamento

Complementa `docs/global/backend/Fase-1/spec.md` (seções 3.3, 4.8, 4.9, 5, 6 e 7).

## 1. Visão técnica
- **Feature:** F04 Exames e armazenamento.
- **Stack:** Spring Web MVC (multipart), Spring Data JPA, `RestClient` (spring-web, `JdkClientHttpRequestFactory`), Bucket4j, Bean Validation. Sem dependência nova.
- **Abordagem:** módulo `crm_data.record` (exames) + `shared/storage`. Sem migrations: usa `core.patient_files` (V011).

## 2. Dados
- **Tabela:** `core.patient_files`; nenhuma alteração.
- **`PatientFile`:** `id` atribuído; `patientId`, `kind` (`@Enumerated(STRING)`), `storageKey`, `originalFilename`, `contentType`, `sizeBytes`, `createdBy` todos `updatable = false`; **`deletedAt` é o único campo editável**; `createdAt`/`updatedAt` `insertable = false, updatable = false` e **sem** `@Generated`; `@Version Long`.
- **Estado em memória:** `UploadRateLimiter` (bucket Bucket4j por `userId`, 10 fichas/min, expurgo como o `LoginRateLimiter`).

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `PatientFileController` | `POST` (multipart), `GET` lista, `GET .../download-url`, `DELETE` |
| `UploadRateLimitInterceptor` + `RecordWebConfig` | Limita `POST /api/v1/patients/*/files` antes da leitura do multipart (`resolve-lazily=true`) |
| `PatientFileService` | Orquestra upload com compensação, lista, URL assinada, remoção lógica; interface pública `storageKeysOf(UUID)` e `purgeObjects(Collection<String>)` |
| `FileSignature` | Detecta o tipo pelos bytes iniciais |
| `FileNames` | Sanitiza o nome original |
| `PatientFileRepository` | Lista de ativos (`deletedAt IS NULL`, `createdAt DESC, id DESC`); busca por `id` e `patientId`; `findStorageKeysByPatientId` |
| `FileStorage` (interface, `shared/storage`) | `save(key, in, contentType, size)`, `load(key)`, `delete(key)`, `deleteAll(keys)`, `createSignedUrl(key, ttl)` |
| `SupabaseFileStorage` | Chamadas REST com a service key (upload, assinatura, remoção em lote), timeouts 3 s/30 s, *streaming* |
| `LocalFileStorage` | Disco temporário; ativo quando `crm.storage.url` está vazio; recusado em `prod` |
| `StorageKeys`, `StorageException` | Validação da chave; erro não verificado de infraestrutura |
| `StorageConfig` / `StartupConfigValidator` (alterado) | Escolhe a implementação; falha a subida em `prod` sem as 3 variáveis |

Configuração: `spring.servlet.multipart.max-file-size=10MB`, `max-request-size=11MB`, `resolve-lazily=true`, `file-size-threshold=256KB`; `server.tomcat.max-swallow-size=12MB`; `crm.storage.signed-url-ttl=60s`; `crm.storage.local-dir` (dev). Nomes das propriedades a conferir no Boot 4.

Regras de rota: `GET` e `DELETE` em `/api/v1/patients/*/files/**` exigem D; o `POST` cai em `authenticated` (D e R).

## 4. Lógica
**Upload:** (1) rate limit; (2) `@PreAuthorize` D ou R; (3) vazio → 400, > 10.485.760 bytes → 413 (`MaxUploadSizeExceededException`), `kind` inválido → 400; (4) `FileSignature` ≠ tipo declarado ou desconhecido → 400, grava o tipo detectado; (5) `FileNames`; (6) `PatientLookup.requireWritable` em transação curta; (7) `fileId = UUID.randomUUID()`, `key = clinicId/patientId/fileId`; (8) `storage.save(...)` fora de transação (falha → 503); (9) `TransactionTemplate`: `PatientFile` com `createdBy = TenantContext.userId()`, `saveAndFlush` (persist sem `SELECT`, sem `refresh`); (10) qualquer falha em 9 → `storage.delete(key)` (falha só logada com `patientId`/`fileId`) e relança; (11) 201 `{id}`.

**Assinar:** busca por `id` + `patientId` + `deletedAt IS NULL`; confere prefixo `clinicId/`; `createSignedUrl(key, 60 s)` fora de transação; 200 `{url, expiresIn: 60}`.

**Remover:** mesma busca; `deletedAt = agora`; `saveAndFlush`; 204 (objeto permanece).

**Para a anonimização (F05):** `storageKeysOf` lista todas as chaves do paciente (inclusive removidas logicamente); `purgeObjects` chama `deleteAll` e propaga `StorageException`.

**`SupabaseFileStorage` (a validar):** `POST {url}/storage/v1/object/{bucket}/{key}` (Bearer, `apikey`, `Content-Type`, `Content-Length`, `x-upsert: false`); `POST .../object/sign/{bucket}/{key}` com `{"expiresIn": 60}` (URL final = `{url}/storage/v1` + `signedURL`); `DELETE .../object/{bucket}` com `{"prefixes": [...]}`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Arquivo vazio, tipo/bytes divergentes, `kind` inválido | 400 `validation` |
| Arquivo > 10 MB | 413 |
| 11º upload em 1 min | 429 + `Retry-After` |
| Recepção em lista, download, remoção | 403 (rota e `@PreAuthorize`) |
| Paciente de outra clínica/inexistente | 404 |
| Paciente anonimizado (antes ou entre a checagem e o `INSERT`, `CR003`) | 409; objeto compensado |
| Storage fora do ar (upload ou URL) | 503 `unavailable` |
| Falha de commit após `save` | Compensação apaga o objeto; se falhar, log do órfão |
| Exame removido, de outro paciente ou outra clínica | 404 |
| Chave fora do padrão ou de outra clínica | `StorageException`/404; nunca assina |

## 6. Segurança e desempenho
- Tipo pelos bytes; tipo gravado = detectado; nome sanitizado; chave só com UUIDs; bucket privado; service key só no backend.
- URL assinada: 60 s, após papel D, tenant e prefixo; `no-store`; fora de log.
- Upload em *streaming*, sem `byte[]` do arquivo; nenhuma transação aberta durante chamadas ao Storage (pool de 10 conexões).
- Meta: upload de 10 MB com `LocalFileStorage` ≤ 5 s.
