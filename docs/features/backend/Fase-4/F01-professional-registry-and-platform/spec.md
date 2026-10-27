# Spec — F01 CRO e plataforma da Fase 4

## 1. Technical Overview
- **Feature:** CRO no perfil do dentista e base compartilhada da Fase 4.
- **Tech Stack:** Spring Boot 4.1.1 / Java 21, Spring Data JPA, Bean Validation, Spring Security. Nenhuma dependência nova.
- **Architecture Approach:** alterações nos módulos `auth`, `record` e `shared`; sem módulo novo. Schema da V031 já aplicado.

## 2. Data Models & Schema
- **Banco:** nenhuma mudança (`ddl-auto=validate`).
- `auth.domain.User`: `+ croNumber` (`@Column(name="cro_number", length=15)`), `+ croState` (`@Column(name="cro_state", length=2)`); `registerProfessional(String cro, String state)` define os dois (ou `null, null`).
- `auth.web.UserResponse`: `+ cro`, `+ croState`.
- `record.domain.AuditAction`: `+ VOID`, `+ DOWNLOAD`. `AuditQueryService.TABLES`: `+ "clinical_documents"`.
- `shared.config.CrmProperties`: `+ ClinicalDocuments(Integer issuesPerMinute, Integer pdfsPerMinute)`, padrão 30/30, chave `crm.clinical-documents.*`.
- Estado em memória: nenhum.

## 3. Component Architecture
- `ProfessionalRegistryRequest` (`auth.web.ProfileRequests`): `cro` (`String`), `croState` (`String`), `version` (`@NotNull Long`); validador de classe `@ValidProfessionalRegistry` (ambos preenchidos ou ambos nulos; `cro` `^\d{1,15}$`; `croState` em `Set` das 27 UFs).
- `MeController`: `PUT /me/professional-registry` → `ProfileService.registerProfessional(...)`.
- `ProfessionalIdentity` (interface em `auth.service`): `Identity current()`, `Optional<Identity> of(UUID userId)`; `record Identity(String name, String cro, String croState)`. Implementação `ProfessionalIdentityService` (`readOnly`, usa o repositório de `User` com `tenantFilter`).
- `shared.error.IntegrityException` (`RuntimeException`, sem texto de documento).
- `GlobalExceptionHandler`: `CR012` (via `SQLException`/`PSQLException` com `SQLState` e mensagem) → 409 `conflict`; `IntegrityException` → 500 com `type` `.../problems/integrity`; `23514` com constraint `ck_cdoc_*`/`ck_users_cro_*` → 500 genérico (constraint no log).
- `SecurityConfig`: regras da seção 5 do contrato.
- `Phase4ArchitectureTest` (reaproveita a infraestrutura de arquitetura da Fase 3; sem ArchUnit novo no pom): `document`/`dashboard` sem ciclo e sem repositório de outro módulo; nenhum módulo antigo depende deles. As regras passam enquanto os pacotes `document`/`dashboard` ainda não existem e passam a valer quando F02 e F05 os criam.

## 4. Core Logic & Algorithms
- **Gravar CRO:** (1) Bean Validation; (2) `ProfileService.registerProfessional(userId do token, request)` em `@Transactional`; (3) carrega o usuário (`tenantFilter`); (4) `version` diferente → `ObjectOptimisticLockingFailureException` → 409; (5) `user.registerProfessional(cro, state)`; (6) `saveAndFlush`; (7) devolve `UserResponse`.
- **Apagar CRO:** os dois `null` passam por (5) com `null, null`; o banco aceita (ambos nulos).
- **`PUT /me`:** inalterado; o `ProfileService.update` não toca nos campos de CRO.
- **ProfessionalIdentity.of(id):** `findById` sob `tenantFilter`; usuário ausente ou de outra clínica → `Optional.empty()`.

## 5. Error Handling & Edge Cases
- Um campo de CRO sem o outro → 400 `validation` (`errors[]` em `cro` e `croState`).
- UF em minúscula ou fora da lista → 400; `cro` com letras ou 16 dígitos → 400 (nunca chega ao `22001`).
- Recepção na rota nova → 403 (`SecurityConfig`); token ausente → 401.
- `42501` do trigger (outro usuário) → 500 (bug; id sai do token).
- Duas gravações simultâneas → uma 200 e uma 409.

## 6. Security & Performance
- Rota exclusiva do dentista; `userId` e `clinicId` só do `TenantContext`; respostas sem `clinicId`.
- CRO é dado profissional, não de saúde: pode aparecer em `GET /me`, mas **não vai a log**.
- Alvo: `PUT /me/professional-registry` p95 ≤ 50 ms.
