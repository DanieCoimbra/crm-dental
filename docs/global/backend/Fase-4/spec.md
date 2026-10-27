# Spec Global (Backend) — Fase 4: Documentos e Dashboard

**Base:** `docs/global/backend/Fase-4/prd.md` (decisões I1–I11), `docs/global/banco/Fase-4/{prd,spec}.md` e `docs/features/banco/Fase-4/F01..F06/contract.md` (contrato do banco), `docs/global/backend/Fase-{0,1,2,3}/spec.md` e o código atual de `back-java/`.
**Natureza:** especificação de contrato. Trechos de código ilustram assinaturas e consultas; as classes são do `dev-engineer-java`.
**Premissas lidas no código real (não assumidas):**
- `AesGcmCipher` grava `base64(versão[1] | IV[12] | cifrado + tag[16])`; `ClinicalNote` usa `EncryptedStringConverter` (cifra no `flush`). Aqui o conversor **não serve**: o `sha256` cobre o texto cifrado, então a cifra é explícita no service.
- `ClinicalNoteService` mostra o padrão: `saveAndFlush` + `entityManager.refresh` (entidades com `@Version`), `PatientLookup`, `TenantContext.userId()`, `@PreAuthorize` na classe, `Clock` injetado.
- `PatientLookup.requireSchedulable(id)` = existe, ativo e não anonimizado (404/409); é o que `BudgetService` usa. O banco repete a guarda (`CR003`/`CR005`).
- `ProblemTypes`: `CONFLICT` = **409**, `BUSINESS_RULE` = **422**. Por isso o PRD (I11 e "Códigos do banco → HTTP") dizia "409 `business-rule`" por engano; vale **409 `conflict`** (ver seção 10).
- `GlobalExceptionHandler.checkViolation`: `23514` residual vira **500** (defeito do backend), com a constraint no log; só duas constraints de orçamento viram 422. O mesmo critério vale para `ck_cdoc_*`.
- `BudgetLookup.approvedTotal(ini, fim)` e `approvedCount(ini, fim)` **já existem** (Fase 3): o dashboard não exige mudança em `commercial` (corrige a tabela de módulos do PRD).
- `TenantContext.role()/userId()/clinicId()`, `ClinicZones.zone()`, `ClinicIdentity.current()`, `UserNames`, `AppointmentLookup` e o filtro Hibernate `tenantFilter` (`clinic_id = :clinicId`) em `TenantAwareEntity` já existem.
- `DocumentRateLimiter` (Bucket4j em memória, 6/min, interceptor por rota) é o modelo dos novos limitadores; `CrmProperties.Documents` guarda os limites com padrão.

---

## 1. Technical Overview

- **Feature:** API de documentos clínicos (atestado, receita, encaminhamento), CRO no perfil do dentista e dashboard.
- **Tech Stack:** Spring Boot 4.1.1 / Java 21, Spring Web MVC, Spring Data JPA/Hibernate 7, Spring Security (JWT), Bean Validation, Bucket4j, OpenPDF 2.4.0 (já no `pom.xml`), PostgreSQL via `crm_app`. **Nenhuma dependência nova.**
- **Architecture Approach:** monólito modular; dois módulos novos (`document`, `dashboard`) por camadas `web → service → repository/domain`; outros módulos só por interfaces públicas; schema, RLS, numeração, auditoria e anonimização são do banco (V031–V035), o backend só consome.

### 1.1 Decisões de spec (além de I1–I11)

| # | Decisão | Valor e motivo |
|---|---|---|
| J1 | Rota do CRO | `GET /me` devolve `cro` e `croState`; a **gravação** é `PUT /me/professional-registry` (`{cro, croState, version}`, só `DENTIST_ADMIN`). `PUT /me` segue como está (nome, e-mail, senha atual). Motivo: no JSON "omitido" e `null` são indistinguíveis (`JsonStrictnessConfig`), e um `PUT /me` que só mande nome/e-mail apagaria o CRO sem querer. Diverge do PRD (I8, US-P4-01, FR-1): ver seção 10 |
| J2 | Pacotes | `crm_data.document` e `crm_data.dashboard`, com `domain`, `repository`, `service`, `web` (dashboard sem `domain`/`repository`) |
| J3 | Cifra | `DocumentCipher` (service) encapsula `AesGcmCipher`; a entidade guarda o **texto cifrado** numa coluna `String` simples. Cifra **uma vez** na emissão; leitura decifra, nunca recifra |
| J4 | Hash | `DocumentIntegrity.sha256(type, patientId, issuedOn, restDays, targetSpecialty, cipherText)` = hex minúsculo de `v1\|TYPE\|patient_id\|issued_on\|rest_days\|target_specialty\|content` (nulos = vazio; `issued_on` ISO `yyyy-MM-dd`; UUID em minúsculas; `content` = o Base64 gravado). Compara em tempo constante (`MessageDigest.isEqual`) |
| J5 | Colunas do banco | `number`, `voided_*`, `anonymized_at`, `created_at`, `updated_at`: `insertable=false`; `UPDATE` só de `voided_at`, `voided_by`, `void_reason`, `version` (`@DynamicUpdate`, demais `updatable=false`) |
| J6 | Download | `DocumentDownloadRecorder` chama `select cast(core.record_document_download(:id) as text)` (função `void`) por consulta nativa na mesma transação do PDF |
| J7 | Limites de uso | `ClinicalDocumentRateLimiter` com dois baldes por usuário (emissão 30/min; PDF 30/min), interceptor nas rotas `POST /patients/*/clinical-documents` e `GET /clinical-documents/*/pdf`; propriedades `crm.clinical-documents.issues-per-minute` e `...pdfs-per-minute` (padrão 30) |
| J8 | Dashboard | Consultas JPQL com o filtro de tenant do Hibernate e a RLS do banco; agrupamento por dia local **em Java** (a semana tem poucas dezenas de linhas), sem SQL nativo de fuso |
| J9 | Tamanho | Texto: `@Size(max = 4000)` **e** `@MaxUtf8Bytes(16000)`; PDF: teto de **4 MB** (acima, 422 `business-rule`, `reason: PDF_TOO_LARGE`) |
| J10 | Nome de arquivo | `atestado-<n>.pdf`, `receita-<n>.pdf`, `encaminhamento-<n>.pdf` (`Content-Disposition: inline; filename=...`) |
| J11 | Erros de integridade | Divergência de `sha256` lança `IntegrityException` → 500 com `type` `.../problems/integrity`, `detail` genérico; log só com `id` do documento e `clinic`, nunca texto |
| J12 | Auditoria | `AuditAction` ganha `VOID` e `DOWNLOAD`; `AuditQueryService.TABLES` ganha `clinical_documents`; `AuditEntryResponse` não muda (ação como texto) |
| J13 | Sem ArchUnit novo no pom | `Phase4ArchitectureTest` reaproveita o teste de arquitetura da Fase 3 (regras de dependência por pacote) |

---

## 2. Data Models & Schema

O backend **não cria nem altera** objetos de banco (`ddl-auto=validate`; Flyway desligado). Contrato: `docs/features/banco/Fase-4/*/contract.md`.

### 2.1 `ClinicalDocument` (nova entidade, tabela `core.clinical_documents`)

| Atributo | Coluna | Tipo Java | Mapeamento |
|---|---|---|---|
| `id` | `id` | `UUID` | `@Id`, gerado pela aplicação (`persist`, sem `merge`) |
| `clinicId` | `clinic_id` | `UUID` | herdado de `TenantAwareEntity` (filtro de tenant) |
| `patientId` | `patient_id` | `UUID` | `updatable=false` |
| `appointmentId` | `appointment_id` | `UUID` | `updatable=false`, nulo |
| `type` | `document_type` | `DocumentType` | `@Enumerated(STRING)`, `length=15`, `updatable=false` |
| `number` | `number` | `Integer` | `insertable=false, updatable=false` (preenchido pelo banco; reler) |
| `issuedOn` | `issued_on` | `LocalDate` | `updatable=false` |
| `restDays` | `rest_days` | `Short` | `smallint`, `updatable=false`, nulo |
| `targetSpecialty` | `target_specialty` | `String` | `length=60`, `updatable=false`, nulo |
| `content` | `content` | `String` | **sem `@Convert`**, `length=22000`, `updatable=false`; guarda o texto **cifrado**; nulo só se anonimizado |
| `sha256` | `sha256` | `String` | `length=64`, `updatable=false` |
| `voidedAt` / `voidedBy` / `voidReason` | `voided_at` / `voided_by` / `void_reason` | `Instant` / `UUID` / `String` | `insertable=false`; **atualizáveis** (única mutação) |
| `anonymizedAt` | `anonymized_at` | `Instant` | `insertable=false, updatable=false` |
| `createdBy` | `created_by` | `UUID` | `updatable=false` |
| `createdAt` / `updatedAt` | `created_at` / `updated_at` | `Instant` | `insertable=false, updatable=false` |
| `version` | `version` | `Long` | `@Version` |

`@Entity @Table(name = "clinical_documents", schema = "core") @DynamicUpdate`; Lombok sem `@Data`/`@ToString` (o conteúdo cifrado e o motivo não vão a log); construtor de emissão `(id, patientId, appointmentId, type, issuedOn, restDays, targetSpecialty, cipherText, sha256, createdBy)`; método `void(Instant now, UUID by, String reason)`.

`DocumentType`: `CERTIFICATE`, `PRESCRIPTION`, `REFERRAL` (nomes idênticos à `ck_cdoc_type`). Estado derivado (sem coluna): `ACTIVE`, `VOIDED`, `ANONYMIZED`.

### 2.2 Alterações em entidades e tipos existentes

| Classe | Alteração |
|---|---|
| `auth.domain.User` | `+ cro_number` (`String`, `length=15`) e `+ cro_state` (`String`, `length=2`), ambos nulos; método `registerProfessional(String cro, String state)` |
| `auth.web.UserResponse` | `+ cro`, `+ croState` (nulos para recepção e para dentista sem CRO) |
| `record.domain.AuditAction` | `+ VOID`, `+ DOWNLOAD` |
| `record.service.AuditQueryService.TABLES` | `+ "clinical_documents"` |

### 2.3 Estado em memória
Só os baldes dos limitadores (Bucket4j, por usuário, descartados por ociosidade). Nada de cache de documento ou de dashboard.

---

## 3. Component Architecture

### 3.1 Layout de diretórios
```
crm_data/
├─ document/
│  ├─ domain/      ClinicalDocument, DocumentType, DocumentState
│  ├─ repository/  ClinicalDocumentRepository, DocumentSummaryRow (projeção)
│  ├─ service/     ClinicalDocumentService, DocumentPdfService, DocumentCipher, DocumentIntegrity, DocumentRules,
│  │               DocumentDownloadRecorder, DocumentPdfRenderer, DocumentPdfModel, ClinicalDocumentRateLimiter,
│  │               IntegrityException
│  └─ web/         ClinicalDocumentController, ClinicalDocumentItemController, DocumentRequests, DocumentResponse,
│                  DocumentSummaryResponse, ClinicalDocumentWebConfig, ClinicalDocumentRateLimitInterceptor, ValidDocument
├─ dashboard/
│  ├─ service/     DashboardService, DashboardWindows
│  └─ web/         DashboardController, DashboardResponse
├─ auth/service/   ProfessionalIdentity (interface pública), ProfessionalIdentityService; ProfileService (+ registerProfessional)
├─ auth/web/       MeController (+ PUT /me/professional-registry), ProfileRequests (+ ProfessionalRegistryRequest), UserResponse
├─ scheduling/service/ AppointmentStats (interface pública), AppointmentStatsService
├─ patient/service/    PatientStats (interface pública), PatientStatsService
├─ record/         AuditAction, AuditQueryService (alterações da seção 2.2)
└─ shared/         GlobalExceptionHandler, ConflictMessages, CrmProperties, SecurityConfig (rotas), Phase4ArchitectureTest
```

### 3.2 Responsabilidades

| Componente | Responsabilidade |
|---|---|
| `ClinicalDocumentService` | `create`, `list`, `get`, `voidDocument`; transações, regras de tipo, cifra, hash, releitura |
| `DocumentPdfService` | `pdf(id)`: lê, confere hash, `record_document_download`, decifra, monta o modelo, chama o renderer |
| `DocumentCipher` | `encrypt(String)`/`decrypt(String)` sobre `AesGcmCipher`; nunca loga |
| `DocumentIntegrity` | `compute(...)` e `verify(doc)` (J4); lança `IntegrityException` |
| `DocumentRules` | constantes (4000 caracteres, 16000 bytes, 1 a 90 dias, 2 a 60, 200, 4 MB, 2000-01-01) e validação por tipo (lista de `FieldError`) |
| `DocumentDownloadRecorder` | chamada nativa de `core.record_document_download` |
| `DocumentPdfRenderer` | regra pura (OpenPDF): modelo → `byte[]`; sem Spring, sem banco, sem relógio |
| `ClinicalDocumentRateLimiter` + interceptor | 429 antes de abrir transação ou gerar PDF |
| `ProfessionalIdentity` | `current()` → `(name, cro, croState)` do **usuário do token** (nome do dentista, CRO e UF) ou `null` nos campos sem CRO |
| `AppointmentStats` / `PatientStats` | leitura agregada para o dashboard (seção 4.7) |
| `DashboardService` | janelas de data, consultas K1–K5 e montagem; corta `monthlyRevenue` para a recepção |
| `DashboardWindows` | cálculo puro de hoje/mês/12 meses/semana no fuso da clínica |

---

## 4. Core Logic & Algorithms

### 4.1 Emitir documento — `POST /patients/{id}/clinical-documents`
1. Interceptor: limite de emissão (30/min por usuário) → 429.
2. Bean Validation (`@ValidDocument`): tipo; `restDays` obrigatório e só em `CERTIFICATE` (1 a 90); `targetSpecialty` obrigatório e só em `REFERRAL` (2 a 60 após `strip`); `content` obrigatório e não vazio em `PRESCRIPTION`/`REFERRAL`, opcional em `CERTIFICATE`; `content` ≤ 4000 caracteres e ≤ 16000 bytes UTF-8; `issuedOn` ≥ 2000-01-01. Erros em lista de campos (400 `validation`).
3. `@Transactional` (`READ COMMITTED`): `patients.requireSchedulable(patientId)` (404/409); `issuedOn` nulo = hoje no `ClinicZones.zone()`; futuro → 422 (`business-rule`, `reason: ISSUED_ON_IN_FUTURE`); `appointmentId` informado → `appointments.requireOfPatient(...)` via `AppointmentLookup` (404 se não existe na clínica; 422 se for de outro paciente).
4. Checagem prévia do CRO: `ProfessionalIdentity.current().cro() == null` → 409 (`ConflictMessages.PROFESSIONAL_REGISTRY_MISSING`). O banco repete (`CR012`).
5. `cipherText = DocumentCipher.encrypt(content)` (texto vazio no atestado vira cifra de `""`, que tem 40 caracteres: atende `char_length ≥ 40` do banco). `sha256 = DocumentIntegrity.compute(...)` sobre o `cipherText`.
6. `persist` (UUID da aplicação, `createdBy = TenantContext.userId()`) → `saveAndFlush` → `entityManager.refresh` (lê `number`, `createdAt`). Os campos `voided*`/`anonymized_at` nunca vêm do cliente.
7. Responde 201 com `DocumentResponse` (conteúdo em claro vindo do `content` da requisição, sem decifrar de novo) e `Location: /api/v1/clinical-documents/{id}`.

### 4.2 Listar e abrir
- **Lista** `GET /patients/{id}/clinical-documents?page&size&type`: `patients.requireExists`; `size` 1 a 50 (padrão 20); ordenação fixa `createdAt DESC, id DESC`; projeção **sem** `content` (`DocumentSummaryRow`); nome do emissor em lote por `UserNames`; sem N+1.
- **Detalhe** `GET /clinical-documents/{id}`: `findById` com o filtro de tenant (404 se não existir ou for de outra clínica); se `anonymizedAt` nulo: `DocumentIntegrity.verify` e `DocumentCipher.decrypt`; se anonimizado: `content`, `restDays`, `targetSpecialty` e `voidReason` nulos na resposta (já nulos no banco), sem decifrar.

### 4.3 Anular — `POST /clinical-documents/{id}/void`
1. Corpo `{version, reason?}` (`reason` ≤ 200 caracteres, `strip`, vazio vira nulo).
2. `@Transactional`: carrega (404); `anonymizedAt != null` ou `voidedAt != null` → 409 (`ConflictMessages.DOCUMENT_STATE`); `version` diferente → 409 (`STALE_VERSION`).
3. `doc.void(clock.instant(), TenantContext.userId(), reason)`; `saveAndFlush`; o `UPDATE` (`@DynamicUpdate`) toca só `voided_at`, `voided_by`, `void_reason`, `version`. A auditoria `VOID` é do trigger do banco.
4. Duas anulações simultâneas: a perdedora recebe 409 por `OptimisticLockingFailureException` (nunca chega ao trigger).

### 4.4 PDF — `GET /clinical-documents/{id}/pdf`
1. Interceptor: limite de PDF (30/min) → 429. `SecurityConfig` exige `DENTIST_ADMIN` (403 antes do banco).
2. `@Transactional`: carrega o documento (404); anonimizado → 409 (`ConflictMessages.DOCUMENT_ANONYMIZED`); `DocumentIntegrity.verify` (500 `integrity` se divergir).
3. `DocumentDownloadRecorder.record(id)` — grava `DOWNLOAD`. Erros do banco: `42501` (bug) / `P0002` (404) / `CR003` (409) / `28000` (500).
4. Decifra o texto; lê `ClinicIdentity.current()`, `ProfessionalIdentity.current()` (nome e CRO do **emissor** do documento: ver nota abaixo), nome do paciente por `PatientLookup.briefs`; monta `DocumentPdfModel`; `DocumentPdfRenderer.render(model)`.
5. `bytes.length > 4 MB` → 422 (`PDF_TOO_LARGE`) e **rollback** (nenhum `DOWNLOAD` fica).
6. Responde 200: `Content-Type: application/pdf`, `Content-Disposition: inline; filename="atestado-12.pdf"`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`. Qualquer falha antes do corpo reverte a transação inteira.

*Nota sobre o dentista do rodapé:* a clínica tem **um** dentista (`ux_users_one_dentist`); o rodapé usa o dentista da clínica pelo `created_by` do documento (nome e CRO atuais, sem snapshot, risco 7 do PRD). `ProfessionalIdentity.of(userId)` serve a esse caso.

### 4.5 PDF — layout (I11)
A4, margens 40/40/40/56, Helvetica `CP1252` não embutida (como o orçamento; caracteres fora da codificação são omitidos).
```
[Nome da clínica]                         (negrito 14)
[Endereço · Telefone]                     (cinza 9)  -- campos nulos omitidos
─────────────────────────────────────────────────────────────
ATESTADO Nº 12   |  RECEITUÁRIO Nº 3  |  ENCAMINHAMENTO Nº 1      (negrito 16, centro)

Paciente: <nome>                                               Emissão: dd/MM/aaaa

CERTIFICATE : "Atesto, para os devidos fins, que <nome> esteve em atendimento
               odontológico nesta data e necessita de <N> dia(s) de afastamento de suas
               atividades."  + (se houver) parágrafo com o complemento
PRESCRIPTION: o texto livre, preservando quebras de linha, sob "Prescrição:"
REFERRAL    : "Encaminho o(a) paciente <nome> para avaliação e conduta em <especialidade>."
               + (se houver) parágrafo com o complemento

                                  ___________________________
                                  <nome do dentista>
                                  CRO <número>/<UF>
rodapé de página: "<Tipo> nº <n> — página x de y"
```
- Documento `VOIDED`: marca diagonal "ANULADO" (cinza claro, 80 pt, 45°) sob o texto em todas as páginas, e a linha "Anulado em dd/MM/aaaa" abaixo do título.
- Metadados: título "<Tipo> nº <n>", autor/criador "CRM Clínica"; **nenhum** nome de paciente em metadado. Sem CPF e sem data de nascimento.
- `<N> dia(s)`: "1 dia" / "N dias". Datas em pt-BR (`dd/MM/yyyy`).

### 4.6 CRO no perfil
- `GET /me`: `UserResponse` com `cro` (`String`) e `croState` (`String`), nulos quando ausentes.
- `PUT /me/professional-registry` (só `DENTIST_ADMIN`): corpo `{cro, croState, version}`; os dois obrigatórios e válidos (`cro` 1 a 15 dígitos; `croState` UF em maiúsculas) **ou** os dois `null` (apaga). `ProfileService.registerProfessional`: carrega o usuário do token (`version` otimista), `user.registerProfessional(...)`, `saveAndFlush`; retorna `UserResponse`. O trigger do banco só permite alterar o **próprio** CRO (`42501` se outro; nunca ocorre aqui porque o id vem do token). `23514` residual = 500.
- Recepção em `PUT /me/professional-registry`: 403 (rota); `GET /me` da recepção traz `cro`/`croState` nulos.

### 4.7 Dashboard — `GET /dashboard?weekOf=YYYY-MM-DD`
**Janelas** (`DashboardWindows`, regra pura; `zone = ClinicZones.zone()`, `today = LocalDate.now(clock.withZone(zone))`):
- hoje: `[today 00:00, today+1 00:00)` no fuso; mês: `[primeiro dia do mês, primeiro dia do mês seguinte)`; 12 meses: `[mês − 12 meses, início do mês)`; semana: `ref = weekOf ?? today`, `segunda = ref.with(previousOrSame(MONDAY))`, `[segunda 00:00, segunda+7 00:00)`. Tudo convertido a `Instant`.
- `weekOf` fora de `[2000-01-01, today + 366 dias]` → 422 (`business-rule`, `reason: WEEK_OUT_OF_RANGE`); formato inválido → 400.

**Consultas** (uma transação `readOnly`, uma conexão, sequenciais; filtro de tenant + RLS):

| KPI | Fonte | Consulta |
|---|---|---|
| K1 `totalPatients` | `PatientStats.activeCount()` | `select count(p) from Patient p where p.active = true` |
| K2 `appointmentsToday` | `AppointmentStats.countByStatus(hojeIni, hojeFim)` | `select a.status, count(a) from Appointment a where a.startsAt >= :ini and a.startsAt < :fim group by a.status`; `total` = soma sem `CANCELED` e `NO_SHOW` |
| K4 `returnRate` | `AppointmentStats.returnStats(mesIni, mesFim, anoIni)` | atendidos: `count(distinct a.patientId)` com `status = COMPLETED` no mês; retornos: os mesmos com `exists (select 1 from Appointment p where p.patientId = a.patientId and p.status = COMPLETED and p.startsAt >= :anoIni and p.startsAt < :mesIni)` |
| K3 `monthlyRevenue` (só dentista) | `BudgetLookup.approvedTotal/approvedCount(mesIni, mesFim)` | já existentes |
| K5 `weekly` | `AppointmentStats.startsAndStatus(semIni, semFim)` | `select a.startsAt, a.status from Appointment a where a.startsAt >= :ini and a.startsAt < :fim`; agrupado em Java por `startsAt.atZone(zone).toLocalDate()` |

- `percent` = `retornos * 100 / atendidos` em `BigDecimal`, escala 1, `HALF_UP`, texto; `null` se `atendidos = 0`.
- Para `RECEPTIONIST`, `BudgetLookup` **não é chamado** e o campo `monthlyRevenue` é omitido da resposta (`@JsonInclude(NON_NULL)`).
- Resposta:
```json
{
  "generatedAt": "2026-10-27T15:04:05Z",
  "timezone": "America/Sao_Paulo",
  "totalPatients": 128,
  "appointmentsToday": { "total": 6, "byStatus": { "SCHEDULED": 2, "CONFIRMED": 1, "IN_PROGRESS": 1, "COMPLETED": 2, "NO_SHOW": 0, "CANCELED": 1 } },
  "returnRate": { "month": "2026-10", "attended": 40, "returning": 25, "percent": "62.5" },
  "monthlyRevenue": { "month": "2026-10", "totalAmount": "18450.00", "approvedCount": 9 },
  "weekly": { "weekStart": "2026-10-26", "weekEnd": "2026-11-01",
    "days": [ { "date": "2026-10-26", "total": 5, "byStatus": { "SCHEDULED": 0, "CONFIRMED": 1, "IN_PROGRESS": 0, "COMPLETED": 4, "NO_SHOW": 0, "CANCELED": 1 } } ] }
}
```
`byStatus` sempre traz os 6 status; `days` sempre traz 7 dias; `Cache-Control: no-store`.

### 4.8 Auditoria
- Emissão (`INSERT`), anulação (`VOID`) e anonimização (`ANONYMIZE`, por documento) são gravadas por **trigger do banco**; `DOWNLOAD` pela função (4.4). O backend só consulta: `GET /audit-log?tableName=clinical_documents[&recordId=]` (já só do dentista).
- Eventos de aplicação (`DocumentEvents`, como `CommercialEvents`): `ISSUED`, `VOIDED`, `DOWNLOADED` com identificadores (nunca texto), consumidos só por logs de auditoria da aplicação (`DocumentAuditLogger`, nível INFO, sem dado pessoal).

### 4.9 Anonimização com documentos
Nenhuma mudança na rota nem em `PatientAnonymizationService`: a função do banco zera os documentos; não há objeto no Storage. Depois da anonimização o detalhe responde `anonymized: true` e o PDF 409. O IT da anonimização passa a verificar isso.

---

## 5. API (resumo; o detalhe por campo vai nos `contract.md`)

| Método e rota | Papéis | Request | Resposta | Erros |
|---|---|---|---|---|
| `PUT /api/v1/me/professional-registry` | D | `{cro, croState, version}` | 200 `UserResponse` | 400 validação · 403 · 409 versão |
| `POST /api/v1/patients/{id}/clinical-documents` | D | `{documentType, issuedOn?, restDays?, targetSpecialty?, content?, appointmentId?}` | 201 `DocumentResponse` | 400 · 403 · 404 · 409 (CRO ausente, paciente anonimizado/inativo) · 422 · 429 |
| `GET /api/v1/patients/{id}/clinical-documents` | D | `?page&size(≤50)&type` | 200 página de `DocumentSummaryResponse` | 400 · 403 · 404 |
| `GET /api/v1/clinical-documents/{id}` | D | — | 200 `DocumentResponse` | 403 · 404 · 500 `integrity` |
| `POST /api/v1/clinical-documents/{id}/void` | D | `{version, reason?}` | 200 `DocumentResponse` | 400 · 403 · 404 · 409 |
| `GET /api/v1/clinical-documents/{id}/pdf` | D | — | 200 `application/pdf` | 403 · 404 · 409 (anonimizado) · 422 (> 4 MB) · 429 · 500 `integrity` |
| `GET /api/v1/dashboard` | D, R | `?weekOf` | 200 `DashboardResponse` | 400 · 422 |
| `GET /api/v1/me` | D, R | — | 200 `UserResponse` (+ `cro`, `croState`) | 401 |

`DocumentResponse`: `id`, `patientId`, `appointmentId`, `documentType`, `number`, `issuedOn`, `restDays`, `targetSpecialty`, `content`, `state` (`ACTIVE`/`VOIDED`/`ANONYMIZED`), `anonymized`, `voidedAt`, `voidedByName`, `voidReason`, `createdAt`, `createdByName`, `version`. **Nunca** `clinicId`, `sha256`, `createdBy` (id) nem o texto cifrado. `DocumentSummaryResponse`: mesmos campos sem `content`, `voidReason` e `version`.

---

## 6. Error Handling & Edge Cases

| Cenário | Resposta |
|---|---|
| Recepção em qualquer rota de documento | 403 antes do banco (`SecurityConfig`); RLS é a segunda barreira |
| `restDays` em receita; especialidade em atestado; tipo desconhecido | 400 `validation` com `errors[]` |
| Receita ou encaminhamento sem texto | 400 `validation` |
| `issuedOn` futuro | 422 `business-rule` (`ISSUED_ON_IN_FUTURE`) |
| Emissor sem CRO (`CR012` ou checagem prévia) | 409 `conflict` (`PROFESSIONAL_REGISTRY_MISSING`) |
| Paciente anonimizado / inativo (`CR003`/`CR005`) | 409 `conflict` |
| Paciente, documento ou consulta de outra clínica | 404 |
| `appointmentId` de outro paciente | 422 (`business-rule`, `APPOINTMENT_OF_OTHER_PATIENT`) |
| Anular documento já anulado ou anonimizado | 409 `conflict` |
| `version` velha / anulação concorrente | 409 `conflict` (`STALE_VERSION`) |
| PDF de documento anonimizado | 409 `conflict` (`DOCUMENT_ANONYMIZED`) |
| PDF acima de 4 MB | 422 `business-rule` (`PDF_TOO_LARGE`), com rollback do `DOWNLOAD` |
| `sha256` divergente | 500 `integrity`; log com `id`, sem texto |
| `23514` residual (`ck_cdoc_*` ou trigger) | 500 (defeito do backend; constraint no log) |
| `42501` num `INSERT`/`UPDATE` | 500 (bug de mapeamento; o 403 sai antes) |
| `28000` (sem `app.user_id`) | 500 |
| `P0002` no download (documento sumiu) | 404 |
| Rate limit | 429 com `Retry-After` |
| Falha ao gerar o PDF depois do `record_document_download` | rollback: nenhuma linha `DOWNLOAD` |
| `weekOf` inválido / fora do intervalo | 400 / 422 |
| Dashboard sem atendimentos | zeros, `percent: null`, 7 dias presentes |
| Chave AES inválida ou versão desconhecida ao decifrar | 500 genérico (`CryptoException`), sem texto |

---

## 7. Security & Performance

### 7.1 Segurança
- **Rotas** (`SecurityConfig`): `hasRole("DENTIST_ADMIN")` em `/api/v1/patients/*/clinical-documents`, `/api/v1/clinical-documents/**` e `PUT /api/v1/me/professional-registry`; `/api/v1/dashboard` e `GET /api/v1/me` para os dois papéis. `@PreAuthorize("hasRole('DENTIST_ADMIN')")` nas classes de service de `document` como segunda barreira; `DashboardService` com `hasAnyRole`.
- **Tenant e papel só do token** (`TenantContext`); DTOs sem `clinicId`, `createdBy`, `sha256`, `number`, `voided*`, `anonymized*`. Entidade sob `tenantFilter` + RLS.
- **Dado de saúde:** texto cifrado na aplicação; nunca em claro em log, exceção, `toString`, metadado de PDF ou nome de arquivo; `reason` de anulação (claro) também nunca em log; testes de higiene validados por mutação.
- **PDF:** `no-store`, `inline`, `nosniff`, nome sem dado pessoal; sem CPF/data de nascimento no corpo.
- **Integridade:** o `sha256` detecta adulteração da linha no banco; chave AES versionada (`CRM_AES_KEYS`), rotação não invalida documentos (a versão está no próprio envelope).
- **Dashboard:** a recepção nunca dispara a consulta de faturamento (corte por papel no service e, além disso, o campo é omitido do JSON).
- **Inventário de risco aceito:** reimpressão usa cabeçalho atual (sem snapshot); recepção lê `budgets` no banco (S16).

### 7.2 Desempenho (metas, ambiente local com Testcontainers, `PerfLimits` dobra com `CI`)

| Operação | Meta p95 | Massa |
|---|---|---|
| `GET /dashboard` | ≤ 150 ms (4 requisições concorrentes) | 100 mil agendamentos, 20 mil pacientes, 1,5 mil orçamentos aprovados |
| `POST` de documento | ≤ 100 ms | 100 mil documentos no banco |
| `GET` detalhe | ≤ 50 ms | idem |
| `GET .../pdf` (render + `record_document_download`) | ≤ 300 ms | idem; texto de 4.000 caracteres |
| Lista do paciente (20 itens) | ≤ 50 ms | idem |

Sem N+1 (nome do emissor em lote); lista sem `content`; o dashboard usa 5 consultas indexadas numa conexão. Pool Hikari = 10: o PDF segura 1 conexão por poucos ms (em memória); IT de pool (como a `Phase3PoolIT`) prova leitura p95 estável com 3 PDFs simultâneos.

---

## 8. Configuração e infraestrutura

- `CrmProperties`: novo bloco `ClinicalDocuments(Integer issuesPerMinute, Integer pdfsPerMinute)` com padrão 30/30 (`crm.clinical-documents.*`); validação no `StartupConfigValidator` (1 a 600).
- `application.yml`: nada além dos padrões; `.env.example` ganha comentários das duas propriedades (opcionais).
- Dockerfile e `docker-compose.yml`: **sem mudança** (nenhuma dependência nova; OpenPDF já presente).
- OpenAPI continua desligado em produção.

---

## 9. Testes (para o `dev-engineer-java` seguir)

| Camada | Cobertura mínima |
|---|---|
| Unitário | `DocumentRules` (limites e combinações por tipo), `DocumentIntegrity` (determinismo, sensibilidade a cada campo, `verify`), `DocumentCipher`, `DashboardWindows` (virada de mês/ano, fuso UTC−3, segunda a domingo, `weekOf`), `DocumentPdfRenderer` (extração de texto com `PdfReader`: frase por tipo, "ANULADO", sem CPF e sem data de nascimento, acentos, 4.000 caracteres em ≤ 2 páginas), `ClinicalDocumentRateLimiter` |
| Web (`@WebMvcTest` + segurança) | matriz papel × endpoint (401/403/2xx), validação (400 com `errors[]`), cabeçalhos do PDF, DTOs sem campos proibidos, `monthlyRevenue` ausente para a recepção |
| Integração (Testcontainers PostgreSQL, V001–V035 reais) | emissão completa e leitura; texto **cifrado** no banco (consulta como administrador); adulteração de `content`/`sha256` → 500 `integrity`; numeração concorrente (20 repetições); anular × anular; `CR012`, `CR003`, `CR005`; auditoria (`INSERT`, `VOID`, `DOWNLOAD`, `ANONYMIZE`) via `GET /audit-log`; falha injetada na geração do PDF → nenhum `DOWNLOAD`; anonimização com documentos; isolamento entre clínicas; RLS sozinha (filtro desligado, papel forjado); `ddl-auto=validate` |
| Dashboard (IT) | massa semeada com todos os status, pacientes ativos/inativos, retorno no mês, orçamentos em meses diferentes; K1–K5 conferidos contra contagens independentes; recepção sem faturamento; semana na virada de mês |
| Desempenho (IT) | metas da seção 7.2, com `EXPLAIN` do banco já provado na Fase 4 do banco |
| Higiene | log sem texto, motivo, nome de paciente ou dentista, CRO (validado por mutação) |
| Arquitetura | `Phase4ArchitectureTest`: `document`/`dashboard` sem ciclo e sem acessar repositórios de outros módulos; nenhum módulo antigo depende deles |

---

## 10. Divergências e decisões em relação ao PRD (a conciliar)

Onde esta spec e o PRD divergem, **vale a spec**; o PRD recebe a nota correspondente na aprovação.

| # | PRD | Spec | Motivo |
|---|---|---|---|
| 1 | I8/US-P4-01/FR-1: `PUT /me` grava `cro` e `croState` | **`PUT /me/professional-registry`** (J1); `GET /me` continua trazendo os campos | No JSON, campo omitido e `null` são indistinguíveis; o `PUT /me` do perfil apagaria o CRO sem querer. Recepção recebe **403** (rota), não 422 |
| 2 | "409 `business-rule`" para `CR012` | **409 `conflict`** | `ProblemTypes.BUSINESS_RULE` é 422; os demais `CRxxx` de paciente são `conflict` |
| 3 | "`23514` de `ck_cdoc_*` → 422; trigger → 409" | `23514` residual → **500** (como o handler atual); a validação prévia devolve 400/422/409 | Regras de borda já cobrem os casos; manter o critério "defeito do backend" |
| 4 | Módulo `commercial` alterado | **Sem mudança**: `BudgetLookup.approvedTotal/approvedCount` já existem | Verificado no código |
| 5 | PDF mostra "o dentista" | dentista da clínica pelo `created_by` do documento, via `ProfessionalIdentity.of(userId)` | Há um dentista por clínica; o dado é atual (sem snapshot) |

## 11. Sugestão de breakdown (a confirmar)

| Feature | Conteúdo |
|---|---|
| `F01-professional-registry-and-platform` | CRO (`User`, `UserResponse`, `PUT /me/professional-registry`, `ProfessionalIdentity`), `AuditAction`/`AuditQueryService`, mensagens de erro e mapeamentos, `CrmProperties`, rotas do `SecurityConfig`, `Phase4ArchitectureTest` |
| `F02-clinical-document-issue-and-read` | entidade, repositório, `DocumentCipher`/`DocumentIntegrity`/`DocumentRules`, emitir, listar e detalhar, anonimização vista pela API |
| `F03-clinical-document-void` | anulação, estados, concorrência |
| `F04-clinical-document-pdf` | renderer, `DocumentPdfService`, `DocumentDownloadRecorder`, limitador e interceptor, atomicidade do `DOWNLOAD` |
| `F05-dashboard` | `AppointmentStats`, `PatientStats`, `DashboardWindows`, `DashboardService`, endpoint |
| `F06-phase4-verification` | matriz, isolamento, RLS sozinha, concorrência, integridade, higiene, desempenho, `ddl-auto=validate`, smoke contra o Supabase real |

## 12. Perguntas abertas

1. Aprova a **divergência 1** (CRO em `PUT /me/professional-registry` em vez de `PUT /me`)? *(Padrão: sim, evita apagar o CRO sem querer.)*
2. O rodapé do PDF pode usar o nome e o CRO **atuais** do dentista (sem snapshot)? *(Padrão: sim, risco 7 do PRD.)*
3. `percent` do retorno com 1 casa decimal e `HALF_UP`? *(Padrão: sim.)*
4. `total` dos dias do gráfico exclui `CANCELED` e `NO_SHOW` (e `byStatus` traz todos)? *(Padrão: sim, igual ao KPI "consultas hoje".)*
