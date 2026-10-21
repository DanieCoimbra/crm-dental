# Spec Técnica Global (Backend) — Fase 1: Núcleo clínico

**Base:** `docs/global/backend/Fase-1/prd.md` (aprovado em 2026-10-21) e a base entregue na Fase 0 (`docs/global/backend/Fase-0/{prd,spec}.md`; código em `back-java/`).
**Contrato do banco:** `docs/global/banco/Fase-1/spec.md` (seções 2, 3 e 6) e as migrations `database/migrations/V007…V013`, lidas para esta spec.
**Escopo:** módulos `patient` e `record`, mais as mudanças em `shared` (sessão do banco, storage, erros).
**Regra:** sem código de aplicação. Há apenas contratos, assinaturas, JSON e configuração ilustrativos; as classes são do `dev-engineer-java`.

> **Fronteira com o banco:** o backend não cria nem altera objetos de banco (`ddl-auto=validate`). Os nomes abaixo são os **definitivos** das migrations V007–V013, já aplicadas no Supabase real.

---

## 1. Visão técnica

- **Feature:** Núcleo clínico (pacientes, prontuário cifrado, exames, auditoria, anonimização).
- **Stack:** Java 21 · Spring Boot 4.1.1 (Spring Framework 7, Hibernate 7) · Spring Data JPA · Spring Security stateless (JWT HS256 da Fase 0) · Bucket4j · Testcontainers. **Nenhuma dependência nova no `pom.xml`**: o cliente do Storage usa `RestClient` (spring-web), o JSON usa o mapper do Spring e a leitura multipart já vem no starter web.
- **Abordagem:** continua a Clean Architecture por módulo da Fase 0 (`web → service → repository`, `domain` sem Spring Web). O controller só traduz HTTP; regra, papel e transação ficam no `service`. O isolamento é em duas camadas (filtro Hibernate + RLS), e o **papel** passa a ser reforçado também pelo banco.

### 1.1 Layout de diretórios (acréscimos e alterações)

```
back-java/src/main/java/crm_data/
├─ shared/
│  ├─ tenant/      TenantHibernateJpaDialect (ALTERADO: 3 variáveis de sessão), TenantIdProvider (+ papel e usuário opcionais)
│  ├─ storage/     FileStorage (ALTERADO), SupabaseFileStorage, LocalFileStorage, StorageException, StorageKeys
│  ├─ error/       DatabaseErrors (NOVO: SQLSTATE/constraint), GlobalExceptionHandler (ALTERADO), ConstraintViolations (+ constantes)
│  ├─ config/      SecurityConfig (ALTERADO: rotas), StorageConfig (NOVO), StartupConfigValidator (ALTERADO: storage em prod)
│  └─ web/         ClinicalCacheHeaders (NOVO: no-store nas rotas clínicas, ver 6.1)
├─ patient/
│  ├─ web/         PatientController, PatientRequests, PatientResponse, PatientSummaryResponse, AnonymizationResponse
│  ├─ service/     PatientService, PatientAnonymizationService, PatientLookup (interface pública), PatientRules (CPF/telefone/datas), ClinicalAuditLogger (eventos)
│  ├─ domain/      Patient, Sex
│  └─ repository/  PatientRepository (JpaRepository + JpaSpecificationExecutor)
└─ record/
   ├─ web/         MedicalHistoryController, ClinicalNoteController, PatientFileController, AuditLogController, *Requests, *Response, UploadRateLimitInterceptor, RecordWebConfig
   ├─ service/     MedicalHistoryService, ClinicalNoteService, PatientFileService (interface pública p/ anonimização), AuditQueryService, FileSignature, FileNames, UploadRateLimiter
   ├─ domain/      MedicalHistory, ClinicalNote, PatientFile, FileKind, AuditLog, AuditAction
   └─ repository/  MedicalHistoryRepository, ClinicalNoteRepository, PatientFileRepository, AuditLogRepository
```

Regras de dependência da Fase 0 mantidas: `record` fala com `patient` só por `PatientLookup`; `patient` fala com `record` só por `PatientFileService`; nenhum repositório é acessado fora do próprio módulo; `shared` não depende dos módulos de negócio. O `record` precisa dos nomes dos usuários para a auditoria e os obtém por uma interface pública nova em `clinic.service` (`UserNames`), e não pelo repositório de `auth`.

---

## 2. Modelo de dados e estado

### 2.1 Mudanças de banco

**Nenhuma.** O backend consome V001–V013. Se o mapeamento exigir algo que o banco não tem, a pendência vai para a função `banco` (ver seção 10).

### 2.2 Mapeamento das entidades (`ddl-auto=validate`)

Todas estendem `TenantAwareEntity` (filtro Hibernate + `clinic_id` do `TenantContext`), usam `@Getter`, `@NoArgsConstructor(access = PROTECTED)` e **setters apenas nos campos editáveis**. Proibidos `@Data`, `@ToString` e `@EqualsAndHashCode` (`lombok.config`). `@Version Long version` em todas, exceto `AuditLog`.

Regras que valem para todas as colunas de `created_at` e `updated_at`: `insertable = false, updatable = false`, **sem** `@Generated`. O `@Generated` faria o Hibernate devolver a linha (`RETURNING` ou `SELECT`), e a RLS nega `SELECT` à recepção em `patient_files`. O `updated_at` é mantido por trigger.

| Entidade (`core.<tabela>`) | Atributos e restrições de mapeamento |
|---|---|
| `Patient` (`patients`) | `id UUID` atribuído pela aplicação. Editáveis (com setter): `name`, `cpf`, `birthDate` (`LocalDate`), `sex` (`@Enumerated(STRING)`, enum `Sex`: `FEMALE`, `MALE`, `OTHER`, `NOT_INFORMED`), `profession`, `phone`, `email`, `address`, `insuranceName`, `insuranceNumber`, `acquaintanceName`, `acquaintancePhone`, `active`, `deactivatedAt` (`Instant`). `createdBy` com `updatable = false` (colunas imutáveis nunca vão no `UPDATE`). **`anonymizedAt` com `insertable = false, updatable = false`**: o `crm_app` não tem grant dessa coluna (`GRANT INSERT/UPDATE` por coluna da V008), e mapeá-la de outra forma gera `permission denied` |
| `MedicalHistory` (`medical_histories`) | `patientId UUID` (`updatable = false`, **sem** `@ManyToOne`: evita carregar `Patient`), `@Convert(converter = EncryptedStringConverter.class) String content`, `createdBy` (`updatable = false`). `UNIQUE (patient_id)` no banco |
| `ClinicalNote` (`clinical_notes`) | Igual ao histórico, mais `notedAt Instant` (editável) |
| `PatientFile` (`patient_files`) | `patientId`, `kind` (`@Enumerated(STRING)`, `FileKind`), `storageKey`, `originalFilename`, `contentType`, `sizeBytes` (`long`), `createdBy`: todos `updatable = false` (a V011 também bloqueia `storage_key`, `content_type` e `size_bytes` por trigger; `kind` e `originalFilename` não têm API de edição). **Único campo editável: `deletedAt`** (remoção lógica) |
| `AuditLog` (`audit_log`) | `@Immutable`; `id Long` (`GENERATED ALWAYS AS IDENTITY`, nunca inserido pelo backend); `userId`, `tableName`, `recordId`, `action` (`@Enumerated(STRING)`, `AuditAction`: `INSERT`, `UPDATE`, `DELETE`, `ANONYMIZE`), `occurredAt`. Sem setters e sem repositório de escrita (interface com apenas métodos de leitura) |

Pontos de validação que o mapeamento deve respeitar:
- `uuid` → `UUID`; `timestamptz` → `Instant`; `date` → `LocalDate`; `varchar` → `String`; `bigint version` → `Long`.
- O texto cifrado gravado em `content` é `Base64(versão[1] | IV[12] | cifra + tag[16])`, padrão Base64 (não URL-safe), que casa com a `CHECK` da V009/V010 (`^[A-Za-z0-9+/]+={0,2}$`, ≥ 40 caracteres). O `AesGcmCipher` da Fase 0 já produz esse formato (mínimo 40 caracteres, para texto vazio).
- `@Size(max = 10000)` e `@Size(max = 5000)` contam unidades UTF-16. Como cada unidade gera no máximo 3 bytes UTF-8 (um par substituto são 2 unidades para 4 bytes), o texto cifrado nunca excede `varchar(54000)` e `varchar(27000)` (cálculo da seção 2.3 do spec do banco).

### 2.3 Estado em memória

- **Stateless:** nenhuma sessão HTTP, nenhum cache de conteúdo clínico decifrado.
- `TenantContext` (por requisição/thread): `clinicId`, `userId`, `role` (já existe), agora também lidos pelo dialeto para o banco.
- `UploadRateLimiter`: um bucket Bucket4j por `userId` (10 fichas por minuto), em memória, com a mesma estratégia de expurgo do `LoginRateLimiter`. Perde o estado a cada restart; aceitável com 1 instância.
- O conteúdo decifrado vive só na entidade durante a transação e na resposta; nunca em `toString`, log ou evento.

---

## 3. Arquitetura de componentes (backend)

### 3.1 Componentes

| Componente | Responsabilidade |
|---|---|
| `TenantHibernateJpaDialect` (alterado) | Logo após abrir a transação, fixa `app.clinic_id`, **`app.role` e `app.user_id`** em uma única instrução (`set_config(..., true)` ×3, mesmo número de idas ao banco que hoje). Papel e usuário ausentes (fluxos de `TenantRunner`: registro, refresh, logout) viram `''`, que o banco lê como `NULL` (falha fechada) |
| `TenantIdProvider` (alterado) | Acrescenta leituras que **não** lançam 401 quando papel ou usuário estão ausentes (`roleOrNull`, `userIdOrNull`); `clinicId()` continua lançando |
| `FileStorage` (alterado) | Interface do storage (assinaturas na seção 3.3) |
| `SupabaseFileStorage` | Implementação de produção: `RestClient` com a service key, bucket privado, timeouts curtos |
| `LocalFileStorage` | Implementação de dev e teste (disco temporário); não existe em `prod` |
| `StorageKeys` | Monta e valida a chave `<clinic_id>/<patient_id>/<uuid>`; rejeita `..`, barras duplicadas, segmentos que não sejam UUID |
| `DatabaseErrors` (novo, `shared/error`) | Percorre a cadeia de causas e devolve o `SQLState` e o nome da constraint; extrai a data do `DETAIL` de `CR002`. Usa só `java.sql.SQLException` (o driver tem escopo `runtime`, então `PSQLException` não é importável) |
| `GlobalExceptionHandler` (alterado) | Novo tratamento de `DataAccessException`/`PersistenceException` por `SQLState` (tabela da seção 5) **sem logar a mensagem do driver** (ela repete CPF e outros valores, ver 6.1) |
| `PatientRules` | CPF (11 dígitos, dígitos verificadores, rejeita sequências iguais), telefone (só dígitos, 10–13), data de nascimento não futura no fuso `America/Sao_Paulo` (constante), nome sem espaços nas pontas |
| `PatientService` | Cadastro, busca, edição, inativação/reativação; regras de papel e de estado |
| `PatientAnonymizationService` | Orquestra a anonimização em duas fases (transação do banco e limpeza do Storage) |
| `PatientLookup` | Interface pública para o `record`: `requireExists(id)` (404) e `requireWritable(id)` (404 se não existe, 409 se anonimizado) |
| `MedicalHistoryService`, `ClinicalNoteService` | Prontuário cifrado, só `DENTIST_ADMIN` |
| `PatientFileService` | Upload com compensação, lista, URL assinada, remoção lógica e, para a anonimização, `storageKeysOf(patientId)` e `purgeObjects(keys)` |
| `FileSignature` | Detecta o tipo real pelos bytes iniciais (JPEG, PNG, WebP, PDF) |
| `FileNames` | Sanitiza o nome original (seção 4.8) |
| `AuditQueryService` | Consulta paginada e leitura de nomes via `UserNames` |
| `UploadRateLimitInterceptor` | Aplica `UploadRateLimiter` ao `POST /patients/*/files` **antes** de o multipart ser lido |
| `ClinicalAuditLogger` | Escuta eventos `AFTER_COMMIT` e registra só identificadores (padrão do `AuthAuditLogger`) |

### 3.2 Endpoints

Prefixo `/api/v1`. Autenticação: `Bearer <access token>`. Papéis: **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`. Nenhum DTO aceita `id`, `clinicId`, `createdBy` ou `role` do corpo; todo `{id}` de rota é resolvido **dentro** do tenant (outra clínica → 404). Todas as respostas dessas rotas levam `Cache-Control: no-store`.

| Método | Rota | Papéis | Request | Response | Erros |
|---|---|---|---|---|---|
| POST | `/patients` | D, R | `CreatePatientRequest` | 201 `PatientResponse` | 400, 401, 409 |
| GET | `/patients?q&status&page&size` | D, R | `q` ≥ 2 caracteres (opcional), `status` = `active` (padrão) \| `inactive` \| `all`, `size` ≤ 50 | 200 `PageResponse<PatientSummaryResponse>` | 400, 401 |
| GET | `/patients/{id}` | D, R | — | 200 `PatientResponse` | 401, 404 |
| PUT | `/patients/{id}` | D, R | `UpdatePatientRequest` (inclui `version`) | 200 `PatientResponse` | 400, 401, 404, 409 |
| PATCH | `/patients/{id}/active` | D | `{active}` | 200 `PatientResponse` | 401, 403, 404, 409 |
| POST | `/patients/{id}/anonymize` | D | — | 200 `{anonymizedAt, storageCleanup}` | 401, 403, 404, 409, 503 |
| GET | `/patients/{id}/medical-history` | D | — | 200 `MedicalHistoryResponse` | 401, 403, 404 |
| PUT | `/patients/{id}/medical-history` | D | `{content, version?}` | 201 (criou) ou 200 (atualizou) `MedicalHistoryResponse` | 400, 401, 403, 404, 409 |
| POST | `/patients/{id}/notes` | D | `{content, notedAt?}` | 201 `NoteResponse` | 400, 401, 403, 404, 409 |
| GET | `/patients/{id}/notes?page&size` | D | `size` ≤ 50 | 200 `PageResponse<NoteResponse>` (mais recentes primeiro) | 401, 403, 404 |
| PUT | `/patients/{id}/notes/{noteId}` | D | `{content, notedAt, version}` | 200 `NoteResponse` | 400, 401, 403, 404, 409 |
| POST | `/patients/{id}/files` | D, R | `multipart/form-data`: `file`, `kind` | 201 `{id}` (somente o id) | 400, 401, 404, 409, 413, 429, 503 |
| GET | `/patients/{id}/files?page&size` | D | `size` ≤ 50 | 200 `PageResponse<FileResponse>` (ativos, mais recentes primeiro) | 401, 403, 404 |
| GET | `/patients/{id}/files/{fileId}/download-url` | D | — | 200 `{url, expiresIn}` (60) | 401, 403, 404, 503 |
| DELETE | `/patients/{id}/files/{fileId}` | D | — | 204 | 401, 403, 404 |
| GET | `/audit-log?tableName&recordId&page&size` | D | `recordId` exige `tableName`; `size` ≤ 50 | 200 `PageResponse<AuditEntryResponse>` | 400, 401, 403 |

Contratos de corpo (ilustrativos):

```json
// POST /patients  (UpdatePatientRequest é igual, mais "version": 3)
{ "name": "Maria Souza", "cpf": "52998224725", "birthDate": "1988-04-12", "sex": "FEMALE",
  "profession": "Professora", "phone": "11987654321", "email": "maria@exemplo.com",
  "address": "Rua A, 10", "insuranceName": "Convênio X", "insuranceNumber": "123456",
  "acquaintanceName": "João Souza", "acquaintancePhone": "11912345678" }

// GET /patients (item da lista)
{ "id": "…", "name": "Maria Souza", "phone": "11987654321", "birthDate": "1988-04-12",
  "insuranceName": "Convênio X", "active": true }

// POST /patients/{id}/anonymize
{ "anonymizedAt": "2046-10-22T13:00:00Z", "storageCleanup": "COMPLETED" }   // ou "PENDING"

// GET .../download-url
{ "url": "https://<projeto>.supabase.co/storage/v1/object/sign/<bucket>/<chave>?token=…", "expiresIn": 60 }

// GET /audit-log (item)
{ "id": 42, "userId": "…", "userName": "Dr. Ana", "tableName": "clinical_notes",
  "recordId": "…", "action": "UPDATE", "occurredAt": "2026-10-21T15:00:00Z" }
```

`PatientResponse` traz todos os campos cadastrais, `active`, `deactivatedAt`, `anonymizedAt`, `createdAt`, `updatedAt` e `version`; **nunca** `clinicId`, `createdBy` nem qualquer campo de prontuário. `FileResponse` não expõe `storageKey`. CPF e telefone saem só com dígitos (a formatação é do front).

Regras de rota no `SecurityConfig` (primeira que casar vale; `@PreAuthorize` nos services é a segunda barreira):

| Regra | Papel |
|---|---|
| `PATCH /api/v1/patients/*/active`, `POST /api/v1/patients/*/anonymize` | D |
| `/api/v1/patients/*/medical-history`, `/api/v1/patients/*/notes/**` | D |
| `GET` e `DELETE` em `/api/v1/patients/*/files/**` | D (o `POST` de upload **não** entra aqui: cai em `authenticated`, D e R) |
| `/api/v1/audit-log/**` | D |
| demais `/api/v1/patients/**` | autenticado (D e R) |

### 3.3 Contrato do `FileStorage` (alterado)

Hoje só existem `save(key, in)`, `load(key)` e `delete(key)`, sem implementação. A Fase 1 passa a ter:

```java
void save(String key, InputStream content, String contentType, long size) throws IOException; // Supabase exige tipo e tamanho
InputStream load(String key) throws IOException;            // mantido; sem uso nesta fase (download é por URL assinada)
void delete(String key) throws IOException;
void deleteAll(Collection<String> keys) throws IOException; // lote; padrão da interface: laço sobre delete
URI createSignedUrl(String key, Duration ttl) throws IOException;
```

Todas as implementações validam a chave com `StorageKeys` (três UUIDs separados por `/`) e lançam `StorageException` (não verificada) para falhas de rede, 4xx/5xx do Storage ou timeout. A chave contém só UUIDs: o nome original do arquivo **nunca** entra nela.

**`SupabaseFileStorage`** (rotas a conferir na primeira execução, ver seção 9):

| Operação | Chamada | Observação |
|---|---|---|
| `save` | `POST {url}/storage/v1/object/{bucket}/{key}` com `Authorization: Bearer <serviceKey>`, `apikey`, `Content-Type`, `Content-Length`, `x-upsert: false` | corpo em *streaming* (sem `byte[]` de 10 MB) |
| `createSignedUrl` | `POST {url}/storage/v1/object/sign/{bucket}/{key}` com `{"expiresIn": 60}` | a URL final é `{url}/storage/v1` + `signedURL` devolvido |
| `deleteAll` | `DELETE {url}/storage/v1/object/{bucket}` com `{"prefixes": [chaves]}` | uma chamada por lote; `delete` usa o mesmo caminho com um item |

Timeouts: conexão 3 s, leitura 30 s. Nenhuma URL, chave ou resposta do Storage vai para log.

---

## 4. Lógica central e algoritmos

### 4.1 Sessão do banco (por transação)

1. O `TenantContextFilter` (Fase 0) já copia `clinic_id`, `sub` e `role` do JWT para o `TenantContext`.
2. Ao abrir cada transação, `TenantJpaTransactionManager` exige tenant (401 sem ele) e o dialeto executa **uma** instrução com três `set_config(..., true)`: `app.clinic_id`, `app.role` (`Role.name()` ou `''`), `app.user_id` (UUID ou `''`). Em seguida habilita o filtro Hibernate.
3. É o **único** ponto que escreve essas variáveis. Nenhum service, controller ou DTO as define.
4. Valores locais à transação: expiram no `commit`/`rollback` e não vazam pelo pool nem pelo Supavisor em modo *transaction*.
5. Efeito no banco: sem `app.role` válido, as políticas negam tudo (inclusive `patients`); sem `app.user_id`, qualquer gravação clínica falha com `28000` (auditoria não pode ser anônima).

### 4.2 Cadastrar paciente (`POST /patients`)

1. `@PreAuthorize("hasAnyRole('DENTIST_ADMIN','RECEPTIONIST')")`.
2. Normalização no construtor canônico dos records (padrão de `RequestText` da Fase 0): CPF e telefones só com dígitos, e-mail em minúsculas, nome com `trim`; CPF vazio vira `null`.
3. Bean Validation: nome 2–120; telefone 10–13 dígitos; CPF com dígitos verificadores; `birthDate` ≥ 1900-01-01 e ≤ hoje (fuso `America/Sao_Paulo`); `sex` na lista (padrão `NOT_INFORMED`); `insuranceNumber` exige `insuranceName`; `acquaintanceName` e `acquaintancePhone` ambos ou nenhum; limites de tamanho iguais às colunas.
4. `id = UUID.randomUUID()`, `createdBy = TenantContext.userId()`. `saveAndFlush`.
5. Violação de `ux_patients_clinic_cpf` → 409 "CPF já cadastrado." (sem repetir o CPF e sem logar a causa).
6. `EntityManager.refresh(patient)` para trazer `createdAt`/`updatedAt` (os dois papéis leem `patients`) e responder 201.

### 4.3 Buscar e listar (`GET /patients`)

1. `q` presente com menos de 2 caracteres (após `trim`) → 400. `q` vazio equivale a ausente.
2. Se `q` contém só dígitos, ponto, hífen e espaços e tem exatamente 11 dígitos → busca por CPF (igualdade, usa `ux_patients_clinic_cpf`).
3. Senão, busca por trecho: `lower(name) LIKE '%' || :q || '%'` com `q` em minúsculas (`Locale.ROOT`) e os caracteres `%`, `_` e `\` escapados. O comparador usa `ESCAPE '\'`. Vale para qualquer tamanho a partir de 2.
4. `status`: `active` (padrão), `inactive`, `all`. Pacientes anonimizados são inativos e aparecem em `inactive|all` com o nome "Paciente anonimizado".
5. Ordenação fixa `name ASC, id ASC`; `page` ≥ 0; `size` 1–50 (padrão 20). Implementada com `Specification` (`JpaSpecificationExecutor`), sempre dentro do filtro de tenant.
6. A projeção da lista traz só os campos de `PatientSummaryResponse`.

> A V008 traz índices trigram e de prefixo, mas `LIKE` não é *leakproof*, então sob RLS o planejador não os usa; a busca é por varredura filtrada (≈ 5 ms com 10 mil pacientes, medido no banco). A meta de 100 ms de ponta a ponta tem folga. Reavaliar se o volume crescer (risco 9 do PRD).

### 4.4 Editar, inativar e reativar

- `PUT /patients/{id}`: carrega por `findById` (a RLS esconde outra clínica → 404). Se `anonymizedAt != null` → 409 ("Paciente anonimizado."). Compara `request.version` com `entity.version`; diferente → 409 (o `@Version` do Hibernate só detecta conflito contra o valor carregado, por isso a comparação explícita). Aplica os campos, `saveAndFlush`, `refresh`, responde.
- `PATCH /patients/{id}/active` (somente D): se anonimizado → 409. Já no estado pedido → 200 sem alterar (idempotente). Inativar grava `active = false` e `deactivatedAt = agora`; reativar grava `active = true` e `deactivatedAt = null` (a `CHECK` ck_patients_deactivated exige essa coerência). Mesmo o banco permitindo que R altere `active`, o backend restringe a D (mais estrito que o banco, por decisão do PRD).
- Paciente inativo continua legível e aceita novos registros clínicos (não há bloqueio por `active`).

### 4.5 Histórico médico (upsert)

`PUT /patients/{id}/medical-history`:
1. D apenas. `PatientLookup.requireWritable(patientId)`: 404 se não existe, 409 se anonimizado.
2. `content` obrigatório, não em branco, até 10.000 caracteres; **não** é aparado nem normalizado.
3. Busca o histórico do paciente. Se não existe: cria (`version` do corpo é ignorada), `201`. Se existe e `version` ausente → 409 ("Histórico já existe; envie a versão atual."); com `version` diferente da atual → 409; igual → atualiza, `200`.
4. `saveAndFlush`; violação de `ux_histories_patient` (criação concorrente) → 409.
5. `GET` sem histórico → 404.
6. A cifra é transparente (`EncryptedStringConverter`); o `refresh` devolve o texto decifrado pelo mesmo conversor.

### 4.6 Notas por atendimento

- **Criar:** `requireWritable`; `notedAt` ausente → `agora`; `notedAt` posterior a `agora + 5 minutos` → 400 (tolerância para relógio do cliente); conteúdo obrigatório até 5.000 caracteres; `id` e `createdBy` do servidor.
- **Listar:** `requireExists`; ordenação `notedAt DESC, id DESC` (índice `ix_notes_timeline`); `size` ≤ 50; decifra só a página.
- **Editar:** nota buscada por `id` **e** `patientId` (outro paciente → 404); compara `version`; atualiza `content` e `notedAt`; sem `DELETE`.
- Paciente anonimizado: criar e editar → 409; listar → vazio (a função apagou as notas).
- Qualquer `UPDATE` reinicia a contagem de retenção de 20 anos (o banco calcula pelo último registro, contrato F06); a documentação da API registra isso.

### 4.7 Sessão e papéis nos fluxos clínicos

Controller → `@PreAuthorize` do service → transação (4.1) → repositório. Para a recepção, o 403 sai **antes** do banco (rota e `@PreAuthorize`). Se o backend errar e deixar uma chamada de R chegar à tabela de prontuário, a RLS devolve 0 linhas ou nega a gravação, e o teste de integração prova isso (seção 8).

### 4.8 Upload de exame (`POST /patients/{id}/files`)

Ordem fixa. O Storage nunca é chamado dentro de uma transação de banco (o pool tem 10 conexões):

1. **Rate limit** (`UploadRateLimitInterceptor`, antes de o multipart ser lido, com `spring.servlet.multipart.resolve-lazily=true`): 11º envio no mesmo minuto pelo mesmo usuário → 429 com `Retry-After`.
2. `@PreAuthorize` D ou R. Validações do arquivo:
   - vazio → 400 (campo `file`);
   - maior que 10.485.760 bytes → 413 (lançando `MaxUploadSizeExceededException` por um só caminho, junto com o limite do multipart);
   - `kind` fora de `RADIOGRAPH`, `INTRAORAL_PHOTO`, `OTHER` → 400;
   - `FileSignature` lê os primeiros bytes e detecta o tipo: JPEG `FF D8 FF`; PNG `89 50 4E 47 0D 0A 1A 0A`; WebP `RIFF` (bytes 0–3) + `WEBP` (bytes 8–11); PDF `%PDF-`. Tipo não reconhecido ou `Content-Type` declarado diferente do detectado → 400. **Grava-se o tipo detectado**, nunca o declarado.
3. `FileNames`: pega só o último segmento do caminho, remove caracteres de controle e separadores, normaliza para NFC, aplica `trim`; vazio vira `arquivo` + extensão do tipo; trunca em 255 mantendo a extensão.
4. `PatientLookup.requireWritable(patientId)` em transação curta e somente leitura: 404 / 409.
5. `fileId = UUID.randomUUID()`; `key = clinicId/patientId/fileId` (`StorageKeys`, `clinicId` do `TenantContext`).
6. `storage.save(key, stream, tipo, tamanho)`. Falha → `StorageException` → 503 `unavailable`.
7. Transação curta (`TransactionTemplate`): constrói `PatientFile` com `createdBy = TenantContext.userId()`, `saveAndFlush` (`persist`, sem `SELECT` prévio porque a `version` é nula e o `id` é atribuído, **sem** `refresh`, **sem** `RETURNING`).
8. **Compensação:** se o passo 7 falhar por qualquer motivo (inclusive `CR003` de um paciente anonimizado entre 4 e 7, ou erro no commit), chama `storage.delete(key)` (falha da limpeza vai para log com `patientId` e `fileId`, sem a chave nem o nome) e relança o erro original.
9. Responde 201 com `{ "id": "<fileId>" }` para D e R.

### 4.9 Listar, baixar e remover exames (somente D)

- **Listar:** `requireExists`; `deletedAt IS NULL`; `createdAt DESC, id DESC`; `size` ≤ 50; campos de `FileResponse`.
- **URL assinada:** busca o exame por `id` **e** `patientId`, com `deletedAt IS NULL` (senão 404). Confere `storageKey.startsWith(clinicId + "/")` (checagem redundante, defesa em profundidade). `storage.createSignedUrl(key, 60 s)` fora de transação. Resposta `{ url, expiresIn: 60 }`; a URL não é registrada em log.
- **Remover:** mesma busca; `deletedAt = agora`; `saveAndFlush`; 204. O objeto **permanece** no Storage (faz parte do prontuário sob retenção) até a anonimização.

### 4.10 Anonimização (`POST /patients/{id}/anonymize`, somente D)

`PatientAnonymizationService` **não** é `@Transactional`; usa `TransactionTemplate` para separar o commit da limpeza do Storage.

1. `@PreAuthorize` D.
2. **Fase do banco** (uma transação):
   a. `SELECT core.anonymize_patient(:id)` por consulta nativa com parâmetro nomeado (sem SQL dinâmico). Devolve o `anonymizedAt` (converter `Instant` ou `OffsetDateTime`).
   b. **Logo depois, na mesma transação**, `PatientFileService.storageKeysOf(patientId)` lista **todas** as `storage_key` do paciente (inclusive as já removidas logicamente, pois a função mantém a chave). Listar depois da função fecha a corrida com um upload concorrente: a função trava a linha do paciente (`FOR UPDATE`), o upload concorrente espera na FK e depois falha com `CR003`, então nenhum exame novo escapa da lista.
   c. Falhas do banco são traduzidas pelo `SQLState` (seção 5): `CR001`, `CR002` (com a data), `CR003`, `P0002`, `42501`.
3. **Depois do commit:** `storage.deleteAll(chaves)`. Sucesso → `COMPLETED`. Qualquer `StorageException` → `PENDING`: a anonimização **não** é desfeita; registra evento de segurança com `patientId` e quantidade de objetos (sem chaves nem nomes). As linhas ficam com `deleted_at` preenchido e `original_filename = 'anonimizado'` para conciliação posterior (non-goal).
4. Publica `PatientAnonymized(clinicId, patientId, objetos, status)`; o `ClinicalAuditLogger` registra depois do commit.
5. Responde 200 `{ anonymizedAt, storageCleanup }`.

### 4.11 Consulta de auditoria (`GET /audit-log`)

1. D apenas. `recordId` sem `tableName` → 400; `tableName` fora das quatro tabelas → 400.
2. Consulta `AuditLog` por clínica (filtro + RLS), filtros opcionais, `occurredAt DESC, id DESC`, `size` ≤ 50. Os índices `ix_audit_clinic_time` e `ix_audit_record` atendem as duas formas.
3. Coleta os `userId` da página e pede os nomes a `UserNames.namesOf(ids)` (uma consulta); usuário não encontrado vira `null` (não ocorre, pois `users` nunca é apagado).
4. Resposta com `AuditEntryResponse`; **nunca** valores de colunas (o log não os tem).

> **Limite de desenho:** o `audit_log` não tem `patient_id`, então a trilha é **por registro** (paciente, histórico, cada nota ou exame). Para o histórico completo de um paciente, o front consulta cada registro. Ver seção 10, item 2.

### 4.12 Isolamento de tenant (duas camadas)

Igual à Fase 0 (spec 4.6), agora com papel: (1) filtro Hibernate por `clinic_id` do token; (2) RLS `FORCE` com `app.clinic_id`, e para as tabelas clínicas também `app.role` e `app.user_id`. `findById`/`EntityManager.find` não aplicam o filtro (limitação conhecida de `TenantAwareEntity`); quem protege esse caminho é a RLS, e o teste de isolamento roda **com o filtro desligado**.

---

## 5. Erros e casos de borda

Formato `application/problem+json` da Fase 0 (`validation`, `unauthorized`, `forbidden`, `not-found`, `conflict`, `business-rule`, `rate-limited`, `internal`, `unavailable`). Nenhuma mensagem contém CPF, nome, conteúdo clínico, chave ou URL.

### 5.1 Tradução de erros do banco (`DatabaseErrors` + handler)

| Origem (SQLSTATE / constraint) | Quando | HTTP / tipo | Mensagem |
|---|---|---|---|
| `CR001` | Anonimizar paciente ativo | 409 `conflict` | "Inative o paciente antes de anonimizar." |
| `CR002` | Retenção vigente | 409 `conflict` | "Retenção legal do prontuário vigente. Liberado em AAAA-MM-DD." (data extraída do `DETAIL` `liberado em AAAA-MM-DD`; se não for possível extrair, a frase sem data) |
| `CR003` | Paciente já anonimizado: edição, reativação, filho novo, segunda anonimização | 409 `conflict` | "Paciente anonimizado." |
| `P0002` | Paciente inexistente na clínica | 404 `not-found` | "Paciente não encontrado." |
| `42501` | Sem permissão (papel) | 403 `forbidden` | texto padrão |
| `28000` | Sessão sem usuário ou clínica | 500 `internal` | genérica; log de erro (indica defeito de configuração) |
| `23505` + `ux_patients_clinic_cpf` | CPF repetido na clínica | 409 `conflict` | "CPF já cadastrado." |
| `23505` + `ux_histories_patient` | Segundo histórico | 409 `conflict` | "Histórico já existe." |
| `23514` (qualquer `CHECK`) | Validação do backend deixou passar | 500 `internal` | genérica; log com o **nome** da constraint, sem valores |
| `23503` (FK) | Vínculo inválido que a checagem prévia não pegou | 500 `internal` | genérica |
| `OptimisticLockingFailureException` | `version` defasada | 409 `conflict` | já existe no handler da Fase 0 |

A tradução específica de `CR001`/`CR002`/`CR003`/`P0002`/`42501` na anonimização é feita **no service** (mensagens específicas); o handler global cobre os mesmos códigos quando surgem em outras operações (por exemplo `CR003` no `UPDATE` de paciente ou no `INSERT` de filho, que o banco levanta ao `flush`).

### 5.2 Cenários

| Cenário | Tratamento |
|---|---|
| Recepcionista chama rota de prontuário, lista/baixa/remove exame, auditoria ou anonimização | 403 pela regra de rota (e `@PreAuthorize`); a RLS é a segunda barreira |
| Recepcionista anexa exame a paciente de outra clínica ou inexistente | 404 (`requireWritable`); o `INSERT` nem chega ao banco |
| Recepcionista anexa exame e tenta ver o que anexou | 403 na listagem; a resposta do `POST` traz só `{id}` |
| `{id}` de paciente, nota ou exame de outra clínica ou de outro paciente | 404 |
| Arquivo vazio, tipo fora da lista, bytes ≠ tipo declarado, `kind` inválido | 400 `validation` com o campo |
| Arquivo > 10 MB | 413 (`handleExceptionInternal`: frase padrão do status) |
| 11º upload em 1 minuto | 429 `rate-limited` + `Retry-After` |
| Storage indisponível no upload, na URL ou na limpeza | Upload e URL: 503 `unavailable`. Limpeza da anonimização: 200 com `storageCleanup: PENDING` |
| Falha no commit após o `save` no Storage | Compensação apaga o objeto; se a compensação também falhar, log com `patientId`/`fileId` (órfão aceito, sem conciliação nesta fase) |
| Paciente anonimizado entre a checagem e o `INSERT` do exame | `CR003` do trigger → 409, objeto compensado |
| Anonimização concorrente | `FOR UPDATE` serializa; a segunda recebe `CR003` → 409 |
| Upload concorrente durante a anonimização | O `INSERT` espera a FK, depois falha com `CR003`; a lista de chaves (feita após a função) não perde nada |
| Edição concorrente de paciente, histórico ou nota | `version` → 409 |
| Criação concorrente do histórico do mesmo paciente | `ux_histories_patient` → 409 |
| Chave AES ausente/inválida na leitura ou texto adulterado | `CryptoException` → 500 `internal`; log sem conteúdo; a aplicação já não sobe sem chaveiro válido |
| Texto de prontuário acima do limite | 400 `validation` |
| `notedAt` futuro (> agora + 5 min) | 400 `validation` |
| Sessão sem `app.role` válido (defeito) | Banco nega tudo: leituras vazias, gravações falham; API devolve 404/500, nunca vaza dados |
| `q` com `%`, `_` ou `\` | Escapados; tratados como texto |
| CPF com dígitos verificadores inválidos | 400 `validation` no campo `cpf` |
| Banco lento/indisponível | 503 `unavailable` (handler da Fase 0) |

---

## 6. Segurança e desempenho

### 6.1 Segurança

- **Papel só do JWT**, definido em um único ponto (4.1). Matriz papel × endpoint testada para 100% das rotas novas.
- **Duas barreiras de papel:** `SecurityConfig` por rota e `@PreAuthorize` no service; a RLS é a terceira. Rotas D-only não dependem da RLS para negar a R.
- **CPF e outros valores fora do log.** A mensagem da exceção do driver para violação de unicidade repete a chave (`Key (clinic_id, cpf)=(…)`). O handler novo e os services **não** logam a mensagem nem a causa de `DataAccessException`/`PersistenceException`; registram só classe, `SQLState` e nome da constraint. Confirmar que `spring.jpa.show-sql=false` e que os *loggers* `org.hibernate.SQL` e `org.hibernate.orm.jdbc.bind` ficam em nível sem parâmetros nos três perfis, e que não há *access log* do Tomcat.
- **Query string.** O CPF pode ser enviado em `GET /patients?q=…`; o Render registra URLs de requisição. Aceito para o portfólio (dados fictícios); alternativa, se houver dados reais: `POST /patients/search` com corpo. Ver seção 10, item 6.
- **Cache.** `Cache-Control: no-store` em `/api/v1/patients/**` e `/api/v1/audit-log/**` (inclui a URL assinada). Conferir que o `SecurityHeadersConfig` da Fase 0 não o sobrescreve.
- **Arquivos.** Tipo pelos bytes, tipo gravado = detectado, nome sanitizado, chave só com UUIDs, bucket privado, service key só no backend, URL assinada de 60 s e só após checar clínica, papel e prefixo da chave. `Content-Type` e disposição do download vêm do Storage; o nome exibido vem do registro sanitizado.
- **SQL.** JPQL/`Specification` parametrizados; SQL nativo apenas em `core.anonymize_patient(:id)` e nas variáveis de sessão (parâmetros ligados).
- **Dados em memória.** O conteúdo decifrado não é guardado fora da entidade; `Patient`, `MedicalHistory`, `ClinicalNote` e `PatientFile` seguem sem `@ToString`.
- **Eventos e logs.** Somente identificadores (`clinicId`, `patientId`, contagens). Nunca nome, CPF, conteúdo, `storage_key`, nome de arquivo ou URL.
- **Limite aceito:** o banco confia no `app.role` informado pelo backend; um backend comprometido contorna a separação (risco 1 do PRD).

### 6.2 Desempenho

| Operação | Meta | Observação |
|---|---|---|
| `GET /patients?q=` | p95 ≤ 100 ms, 10 mil pacientes na clínica, 4 concorrentes, local | consulta ≈ 5 ms no banco; `count(*)` da página incluído |
| `GET /patients` sem filtro | p95 ≤ 100 ms | índice `ix_patients_clinic_name` |
| Linha do tempo de notas | p95 ≤ 100 ms com 500 notas | `ix_notes_timeline`; decifra só até 50 itens |
| Upload de 10 MB com `LocalFileStorage` | ≤ 5 s | *streaming*, sem `byte[]` do arquivo no heap |
| Anonimização com 500 notas | dentro do `statement_timeout` de 15 s | volume real: dezenas |
| Sessão do banco | 1 ida a mais por transação? **Não** | as três variáveis vão na mesma instrução que hoje só fixa o tenant |

Pool Hikari continua com 10 conexões: por isso o upload ao Storage ocorre **fora** de transação e a limpeza pós-anonimização acontece depois do commit.

---

## 7. Configuração, Docker e Render

**`application.yml` (acréscimos):**

| Propriedade | Valor | Motivo |
|---|---|---|
| `spring.servlet.multipart.max-file-size` | `10MB` | teto do arquivo (S5 do banco) |
| `spring.servlet.multipart.max-request-size` | `11MB` | arquivo + campos |
| `spring.servlet.multipart.resolve-lazily` | `true` | o *rate limit* roda antes da leitura do corpo |
| `spring.servlet.multipart.file-size-threshold` | `256KB` | acima disso o Tomcat grava em disco temporário em vez da memória |
| `server.tomcat.max-swallow-size` | `12MB` | evita *connection reset* em vez de 413 |
| `crm.storage.signed-url-ttl` | `60s` | validade da URL assinada |
| `crm.storage.local-dir` | vazio | só dev/teste: ativa `LocalFileStorage` quando `crm.storage.url` está vazio |

Nomes das propriedades de multipart no Spring Boot 4 a **conferir no primeiro build** (seção 9).

**Variáveis de ambiente:** nenhuma nova. `CRM_STORAGE_URL`, `CRM_STORAGE_SERVICE_KEY` e `CRM_STORAGE_BUCKET` já existem em `CrmProperties.Storage`, `.env.example` e `docker-compose.yml`. O `StartupConfigValidator` passa a **falhar a subida em `prod`** se qualquer uma estiver vazia e a recusar `LocalFileStorage` em `prod`.

**Dockerfile e docker-compose:** sem alteração. O contêiner roda como não-root; o diretório temporário do multipart (`java.io.tmpdir`) é efêmero e suficiente. No Render, definir as três variáveis `CRM_STORAGE_*` no serviço Web.

**Pré-requisito manual (ação do usuário, fora do código):** bucket privado do Supabase com limite de 10 MB e os quatro tipos permitidos, sem política para `anon`/`authenticated` (checklist no `database/README.md`).

---

## 8. Estratégia de testes (para o `dev-engineer-java`)

| Camada | Ferramenta | Casos críticos |
|---|---|---|
| Unitário | JUnit 5 + Mockito | `PatientRules` (tabela de CPFs válidos, inválidos e sequências iguais; telefones; datas); `FileSignature` e `FileNames` (JPEG/PNG/WebP/PDF reais, tipo declarado diferente, nome com `../`, controle e Unicode); `StorageKeys`; `DatabaseErrors` (extração de `SQLState`, constraint e data do `DETAIL`); `PatientService` (anonimizado → 409, `version` defasada → 409, idempotência do `active`); `PatientAnonymizationService` (erros `CR001`/`CR002`/`CR003`/`P0002`, `PENDING` quando o Storage falha, ordem: função → listagem → commit → limpeza); `PatientFileService` (compensação em falha do banco; nada chama o Storage dentro de transação); `UploadRateLimiter` |
| Web | `@WebMvcTest` + `spring-security-test` | **Matriz papel × endpoint** completa (401/403/2xx) para as 16 rotas; DTOs ignoram `clinicId`/`createdBy`/`role`/`id`; `POST /files` aceita D e R e devolve só `{id}`; formato `ProblemDetail`; `no-store` nas rotas clínicas; 413 e 429 |
| Integração | Testcontainers PostgreSQL com `database/bootstrap` e `database/migrations` (V001–V013) | (1) `ddl-auto=validate` passa com as 5 entidades (fecha o teste 57 do banco); (2) sessão: `app.clinic_id`, `app.role`, `app.user_id` definidos e **sem vazar** com pool de 1 conexão; fluxos `TenantRunner` (sem papel) deixam `''`; (3) **isolamento com filtro desligado**: duas clínicas, 0 linhas da outra nas 5 tabelas; (4) **papel forjado**: com `app.role = RECEPTIONIST` e filtro desligado, `medical_histories`/`clinical_notes`/`patient_files`/`audit_log` devolvem 0 linhas e as gravações clínicas falham; `app.role` ausente ou inválido nega até `patients`; (5) **cifra em repouso**: `SELECT content` por SQL nativo não contém o texto e casa com a regex Base64; (6) recepção anexa exame sem `RETURNING`/`SELECT` (inspeção de SQL com `RecordingStatementInspector`); (7) upload: sucesso, tipo falsificado, > 10 MB, compensação quando o `INSERT` falha (paciente anonimizado) e o objeto é apagado; (8) CPF duplicado → 409 e **o CPF não aparece no log** (captura de log); falha de cifra não registra o conteúdo; (9) anonimização: ativo → `CR001`, retenção vigente → `CR002` com a data, vencida (fixture recua `updated_at` pela conexão do dono, como o teste 47 do banco) → passa, depois nada pessoal permanece, nova edição → 409, objetos apagados, e `PENDING` com um `FileStorage` que falha; (10) auditoria: cada gravação gera 1 linha, sem valores; sem `app.user_id` a gravação falha; (11) `GET /audit-log` pagina, filtra e resolve nomes; (12) busca com 10 mil pacientes: p95 ≤ 100 ms com 4 concorrentes (limite sobrescrevível por `-Dperf.limit-ms`, como o `LoginPerformanceIT`) |
| Segurança | MockMvc/IT | Rate limit de upload; `{id}` cruzando clínicas sempre 404; rotas D-only retornam 403 para R sem tocar o banco (contagem de statements = 0); `no-store` presente |

**Critérios de saída da Fase 1:** `mvn verify` verde em máquina limpa (Docker ligado); matriz de papéis 100%; testes de isolamento e de papel forjado verdes; cobertura ≥ 80% nos *services* de `patient` e `record`; nenhum texto clínico, CPF ou URL assinada em log; histórias US-P1-01 a US-P1-11 atendidas. Os testes existentes da Fase 0 que afirmam o texto SQL de `app.clinic_id` (`TenantTransactionsIT`, `TenantPoolIT`, `RecordingStatementInspector`) precisam ser atualizados para a instrução de três variáveis.

---

## 9. Pontos a validar antes de codar

1. **API REST do Supabase Storage** (rotas e formato): upload (`POST /object/{bucket}/{key}`), assinatura (`POST /object/sign/{bucket}/{key}` com `expiresIn`, resposta `signedURL`) e remoção em lote (`DELETE /object/{bucket}` com `prefixes`). Conferir com um bucket real antes de implementar `SupabaseFileStorage`; confirmar também se o parâmetro `download=<nome>` da URL assinada ajuda a forçar o *download*.
2. **Propriedades de multipart do Spring Boot 4** (`resolve-lazily`, `file-size-threshold`) e se `MaxUploadSizeExceededException` chega ao `ResponseEntityExceptionHandler` como 413.
3. **Hibernate 7:** tipo devolvido pela consulta nativa de `timestamptz` (`Instant` ou `OffsetDateTime`); `@Immutable` combinado com o filtro de tenant em `AuditLog`; `saveAndFlush` de entidade com `id` atribuído e `version` nula faz `persist` sem `SELECT` (conferir no IT da recepção, risco 2 do PRD).
4. **Spring Security 7:** semântica de `/**` nos *matchers* de caminho (se `/files/**` casa com `/files`) e a ordem das regras GET/DELETE × POST.
5. **`SecurityHeadersConfig`:** se o `Cache-Control: no-store` das rotas de auth pode ser estendido às rotas clínicas sem conflito.
6. **Supavisor em modo *transaction*:** nada novo (as variáveis são locais à transação), mas repetir o IT de vazamento com o pooler real quando possível.
7. **Bucket do Supabase** criado conforme o checklist (ação do usuário); sem ele, `PatientFileService` só passa nos testes com `LocalFileStorage`.

---

## 10. Divergências e lacunas em relação aos documentos anteriores

| # | Item | Documento anterior | Esta spec |
|---|---|---|---|
| 1 | Ordem da lista de chaves na anonimização | Banco, 6.3 item 3, e PRD: "antes da chamada, listar as `storage_key`" | Lista **depois** da função, na mesma transação (a função trava a linha do paciente, então a lista não perde exame de upload concorrente). O contrato permanece equivalente; sugere-se atualizar o texto do banco |
| 2 | Trilha por paciente | PRD US-P1-10 fala de auditoria "de um paciente" | O `audit_log` não tem `patient_id`: a consulta é **por registro**. Ver o histórico de um paciente exige consultar cada registro. Se a tela precisar de visão única, é pedido de migration para a função `banco` |
| 3 | `FileStorage` | Javadoc: "sem implementação nesta fase" | Assinaturas ampliadas (tipo, tamanho, lote, URL assinada); compatível, pois não havia implementação |
| 4 | Busca por 1–2 caracteres | Banco: índice de prefixo para 1–2 caracteres | PRD fixou `q` ≥ 2 e a busca é sempre por trecho; o índice de prefixo e o trigram não são usados sob RLS (já registrado como risco 9) |
| 5 | Paginação | Banco sugere keyset | PRD escolheu `PageResponse` por `page/size` (volume pequeno) |
| 6 | CPF em query string | PRD: `GET /patients?q=` | Aceito para o portfólio; trocar por `POST /patients/search` se houver dados reais |
| 7 | `PATCH active` | O banco permite a R alterar `active` em `patients` | Backend restringe a D (mais estrito, por PRD) |
| 8 | `AGENTS.md` | Vazio | Segue vazio; premissas lidas dos PRDs e specs |

---

## 11. Fatiamento para o `breakdown` (referência)

| Feature | Conteúdo | Depende de |
|---|---|---|
| F01 | Sessão do banco e plataforma: dialeto com 3 variáveis, `DatabaseErrors`/handler, mapeamento `validate` do esqueleto, `no-store`, config de multipart | Fase 0 |
| F02 | Pacientes: cadastro, busca, edição, inativação | F01 |
| F03 | Prontuário cifrado: histórico e notas | F01, F02 |
| F04 | Exames e `SupabaseFileStorage`/`LocalFileStorage` | F01, F02 |
| F05 | Auditoria (consulta) e anonimização | F02, F03, F04 |
| F06 | Verificação da fase: matriz de papéis, isolamento com filtro desligado, papel forjado, desempenho, higiene de log | F01–F05 |
