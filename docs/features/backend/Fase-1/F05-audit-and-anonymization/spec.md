# Spec — F05 Auditoria e anonimização

Complementa `docs/global/backend/Fase-1/spec.md` (seções 4.10, 4.11, 5.1 e 5.2).

## 1. Visão técnica
- **Feature:** F05 Auditoria e anonimização.
- **Stack:** Spring Web MVC, Spring Data JPA (consulta nativa para a função), `TransactionTemplate`, eventos Spring (`@TransactionalEventListener`), Spring Security.
- **Abordagem:** `PatientAnonymizationService` em `crm_data.patient`; `AuditQueryService` em `crm_data.record`; `UserNames` em `crm_data.clinic.service`. Sem migrations: usa V012 e V013.

## 2. Dados
- **Tabelas:** `core.audit_log` (leitura) e `core.patients`/`patient_files`/`medical_histories`/`clinical_notes` (alteradas **só** pela função). Nenhuma alteração de schema.
- **`AuditLog`:** `@Immutable`; `id Long` (identidade, nunca inserido pelo backend); `userId`, `tableName`, `recordId`, `action` (`AuditAction`), `occurredAt`; sem setters.
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `PatientController` (rota adicional) | `POST /patients/{id}/anonymize` → `AnonymizationResponse {anonymizedAt, storageCleanup}` |
| `PatientAnonymizationService` | Orquestração em duas fases; `@PreAuthorize` D; não é `@Transactional` |
| `PatientFileService` (F04) | `storageKeysOf(UUID)` e `purgeObjects(Collection<String>)` |
| `AuditLogController` | `GET /audit-log` |
| `AuditQueryService` | Consulta paginada com filtros opcionais; junta os nomes por `UserNames` |
| `AuditLogRepository` | Somente métodos de leitura (sem `save`/`delete`) |
| `UserNames` (`clinic.service`) | `Map<UUID,String> namesOf(Collection<UUID>)` sobre `UserRepository.findAllById` |
| `ClinicalAuditLogger` | Escuta `Patient.Anonymized` e `Patient.StorageCleanupPending` após o commit; log só com ids |
| `DatabaseErrors` (F01) | `SQLState` e data do `DETAIL` de `CR002` |

Regras de rota: `POST /api/v1/patients/*/anonymize` e `/api/v1/audit-log/**` exigem D.

## 4. Lógica
**Anonimização:**
1. `@PreAuthorize` D.
2. `TransactionTemplate.execute`: (a) consulta nativa `SELECT core.anonymize_patient(:id)` com parâmetro nomeado → `anonymizedAt` (aceitar `Instant` ou `OffsetDateTime`); (b) na mesma transação, `PatientFileService.storageKeysOf(patientId)`; retorna `(anonymizedAt, chaves)`.
3. Falha em 2a: extrair `SQLState` com `DatabaseErrors` e lançar `BusinessException` — `CR001` → 409 "Inative o paciente antes de anonimizar."; `CR002` → 409 "…Liberado em AAAA-MM-DD."; `CR003` → 409; `P0002` → 404; `42501` → 403; outros → relança.
4. Após o commit (fora do `TransactionTemplate`): `PatientFileService.purgeObjects(chaves)`. Sucesso → `COMPLETED`; `StorageException` → `PENDING` e evento `Patient.StorageCleanupPending(patientId, quantidade)`.
5. Publica `Patient.Anonymized(clinicId, patientId, quantidade, status)`; responde 200.

**Auditoria:** valida filtros (`recordId` exige `tableName`; tabela na lista) → página `AuditLog` por clínica (`occurredAt DESC, id DESC`) → `UserNames.namesOf(userIds da página)` → `AuditEntryResponse`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Recepção em qualquer rota | 403 pela rota e `@PreAuthorize` |
| Paciente de outra clínica/inexistente | `P0002` → 404 |
| Paciente ativo | `CR001` → 409 |
| Retenção vigente | `CR002` → 409 com a data |
| Já anonimizado / chamadas concorrentes | `CR003` → 409 (a função usa `FOR UPDATE`) |
| Storage falha após o commit | 200 `PENDING`; anonimização mantida; evento sem chaves |
| Upload concorrente durante a anonimização | O `INSERT` falha com `CR003`; a lista (feita após a função) não perde objeto |
| `recordId` sem `tableName`, ou tabela fora da lista | 400 |
| Usuário da trilha sem nome encontrado | `userName = null` |
| `app.user_id` ausente | `28000` → 500 (defeito de configuração) |

## 6. Segurança e desempenho
- Só D; SQL nativo apenas em `core.anonymize_patient(:id)` com parâmetro ligado; a função já valida papel, clínica e retenção.
- Logs e eventos com `clinicId`, `patientId` e contagens; nunca nome, CPF, chave ou arquivo.
- `audit_log` sem dado pessoal; RLS limita à clínica e ao papel D; entidade sem escrita.
- Anonimização dentro do `statement_timeout` de 15 s; auditoria usa `ix_audit_clinic_time` e `ix_audit_record`.
