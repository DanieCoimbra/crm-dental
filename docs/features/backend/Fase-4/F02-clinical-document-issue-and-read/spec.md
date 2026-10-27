# Spec — F02 Emissão e leitura de documentos clínicos

## 1. Technical Overview
- **Feature:** módulo `crm_data.document` (emitir, listar, detalhar).
- **Tech Stack:** Spring Web MVC, Spring Data JPA/Hibernate 7, Bean Validation, Bucket4j, `AesGcmCipher`. Nenhuma dependência nova.
- **Architecture Approach:** camadas `web → service → repository/domain`; schema e regras de integridade no banco (V032–V034); backend cifra, calcula o hash e traduz erros.

## 2. Data Models & Schema
- **Tabela:** `core.clinical_documents` (não alterada). Mapeamento da entidade `ClinicalDocument` (`@Entity @Table(name="clinical_documents", schema="core") @DynamicUpdate`, estende `TenantAwareEntity`):

| Atributo | Coluna | Tipo | Observação |
|---|---|---|---|
| `id` | `id` | `UUID` | gerado na aplicação, `persist` |
| `patientId`, `appointmentId` | `patient_id`, `appointment_id` | `UUID` | `updatable=false`; appointment nulo |
| `type` | `document_type` | `DocumentType` | `STRING`, `length=15` |
| `number` | `number` | `Integer` | `insertable=false, updatable=false` |
| `issuedOn` | `issued_on` | `LocalDate` | `updatable=false` |
| `restDays` | `rest_days` | `Short` | nulo |
| `targetSpecialty` | `target_specialty` | `String(60)` | nulo |
| `content` | `content` | `String(22000)` | **cifrado**, sem `@Convert` |
| `sha256` | `sha256` | `String(64)` | |
| `voidedAt/By`, `voidReason` | `voided_*`, `void_reason` | `Instant/UUID/String` | `insertable=false`; únicos atualizáveis (F03) |
| `anonymizedAt`, `createdAt`, `updatedAt` | idem | `Instant` | `insertable=false, updatable=false` |
| `createdBy` | `created_by` | `UUID` | `updatable=false` |
| `version` | `version` | `Long` | `@Version` |

- Lombok sem `@Data`/`@ToString`; construtor de emissão e método `void(...)` (usado na F03).
- `DocumentState` derivado: `ANONYMIZED` se `anonymizedAt != null`; `VOIDED` se `voidedAt != null`; senão `ACTIVE`.

## 3. Component Architecture
- `ClinicalDocumentService`: `create(patientId, request)`, `list(patientId, type, pageable)`, `get(id)`.
- `DocumentCipher`: `encrypt/decrypt` sobre `AesGcmCipher`; nunca loga.
- `DocumentIntegrity`: `compute(type, patientId, issuedOn, restDays, targetSpecialty, cipherText)`; `verify(doc)`; `MessageDigest.isEqual`.
- `DocumentRules`: constantes (4000 caracteres, 16000 bytes, 1 a 90 dias, 2 a 60, 2000-01-01) e validação por tipo.
- `ClinicalDocumentRateLimiter` (balde `issues`) e `ClinicalDocumentRateLimitInterceptor` na rota `POST /patients/*/clinical-documents`; `ClinicalDocumentWebConfig` registra o interceptor.
- `ValidDocument` (validador de classe) para `DocumentRequests.Create`.
- Controllers: `ClinicalDocumentController` (`/patients/{id}/clinical-documents`), `ClinicalDocumentItemController` (`/clinical-documents/{id}`).
- Layout: `document/{domain,repository,service,web}`.

## 4. Core Logic & Algorithms
- **Emitir:** (1) interceptor 429; (2) `@ValidDocument` (erros em lista); (3) `@Transactional(READ_COMMITTED)`: `PatientLookup.requireSchedulable`; `issuedOn ?? today(ClinicZones.zone())`; futuro → 422; `appointmentId` → `AppointmentLookup.requireOfPatient` (404/422); (4) `ProfessionalIdentity.current().cro() == null` → 409; (5) `cipherText = DocumentCipher.encrypt(content ?? "")`, `sha256 = DocumentIntegrity.compute(...)`; (6) `persist` com `createdBy = TenantContext.userId()`, `saveAndFlush`, `refresh` (lê `number`, `createdAt`); (7) 201 com o conteúdo em claro da requisição (sem decifrar de novo).
- **Listar:** `PatientLookup.requireExists`; consulta paginada com projeção sem `content`; `size` 1 a 50; `UserNames.byIds` em lote.
- **Detalhar:** `findById` (tenant filter); se `anonymizedAt == null`: `verify` e `decrypt`; senão campos clínicos nulos.
- **Anonimização:** nenhuma mudança em `PatientAnonymizationService`; o IT verifica o efeito.
- **Evento:** `DocumentEvents.ISSUED(documentId, patientId, type, userId)` após o commit; `DocumentAuditLogger` em INFO sem dado pessoal.

## 5. Error Handling & Edge Cases
- Combinação inválida por tipo, texto vazio em receita/encaminhamento, texto acima de 4000 caracteres ou 16000 bytes → 400 `validation` (`errors[]`).
- `issuedOn` futuro → 422; anterior a 2000-01-01 → 400.
- Paciente de outra clínica ou inexistente → 404; inativo/anonimizado → 409; `CR003`/`CR005` do banco → 409.
- `CR012` → 409; `42501`/`28000`/`23514` residual → 500.
- Dois `POST` simultâneos → números distintos (o segundo espera o lock); falha no commit não consome número.
- Chave AES inválida → 500 `CryptoException` genérico.

## 6. Security & Performance
- `@PreAuthorize("hasRole('DENTIST_ADMIN')")` na classe do service; `SecurityConfig` barra recepção antes do banco; RLS e `tenantFilter` como barreiras seguintes.
- DTOs sem `clinicId`, `sha256`, `number`, `voided*`, `anonymized*`, `createdBy`; entrada de `content` é texto simples (sem HTML; o PDF não interpreta marcação).
- Texto e `reason` nunca em log, exceção ou `toString`.
- Metas: emissão p95 ≤ 100 ms, detalhe ≤ 50 ms, lista (20) ≤ 50 ms com 100 mil documentos; sem N+1.
