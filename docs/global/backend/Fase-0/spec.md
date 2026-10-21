# Spec Técnica Global (Backend) — CRM Clínica Dental

**Base:** `docs/global/backend/prd.md` (aprovado em 2026-10-19). Esta spec cobre a plataforma e a **Fase 0**. As fases 1–4 são detalhadas por feature no `breakdown`.
Sem código de aplicação: apenas contratos e configuração ilustrativos.

> **Fronteira com o banco:** schema, papéis, RLS, funções e migrations pertencem à função **banco** (`postgres-dba-architect`, em `docs/global/banco/` e `docs/features/banco/`). Este documento só registra o **contrato que o backend exige do banco** (seção 2). Os nomes de objetos são os **definitivos** do dicionário do banco (`docs/features/banco/F03-core-tables/contract.md` e `docs/global/banco/spec.md`, seção 8).

---

## 1. Visão técnica

- **Feature:** Fundação do backend (plataforma, segurança, multi-tenant, auth, clínica e usuários).
- **Stack:** Java 21 · Spring Boot 4.1.1 (Spring Framework 7, Hibernate 7) · PostgreSQL 16+ · Spring Security (stateless) · Nimbus JOSE (via `oauth2-resource-server`) · Bucket4j · Testcontainers.
- **Abordagem:** monólito modular, pacote base `crm_data`, um deploy no Render, banco único. Clean Architecture por feature: `web → service → repository`, com o `domain` sem dependência de Spring Web.

> **Nota sobre versões:** os starters do Spring Boot 4 são modulares (por exemplo `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`). Os nomes abaixo devem ser **conferidos no primeiro `mvn verify`**; versões de terceiros fora do BOM (springdoc, Bucket4j, Testcontainers) precisam ser confirmadas como compatíveis com Boot 4.1.

### 1.1 `pom.xml`

Ajustes no arquivo atual:
- `groupId` `crm-dental` (mantido), `artifactId` `crm-api` (hoje `demo`), `name` e `description` preenchidos; remover `<licenses>`, `<developers>`, `<scm>` vazios.
- Remover `<repositories>` e `<pluginRepositories>` de snapshots (o parent 4.1.1 é GA e vem do Maven Central).
- `java.version` 21.

| Dependência | Escopo | Uso |
|---|---|---|
| `spring-boot-starter-webmvc` | compile | API REST |
| `spring-boot-starter-validation` | compile | Bean Validation |
| `spring-boot-starter-data-jpa` | compile | JPA/Hibernate |
| `postgresql` | runtime | Driver |
| `spring-boot-starter-security` | compile | Autenticação e autorização |
| `spring-boot-starter-oauth2-resource-server` | compile | Validação do JWT (Nimbus), emissão via `JwtEncoder` |
| `spring-boot-starter-actuator` | compile | Health |
| `bucket4j` (core) | compile | Rate limit em memória (instância única no Render) |
| `springdoc-openapi` (starter webmvc) | compile, **ativo só no perfil `dev`** | OpenAPI |
| `lombok` | compile-only (`optional`) | `@Getter`, `@Setter`, `@NoArgsConstructor(access = PROTECTED)` nas entidades e `@RequiredArgsConstructor` na injeção por construtor. **Proibidos** `@Data`, `@ToString` e `@EqualsAndHashCode` (validado em `lombok.config`): `equals`/`hashCode` sobre campos mutáveis quebram o Hibernate, e `toString` vazaria campos sensíveis. `clinicId` fica sem setter e `passwordHash` sem getter. Records continuam sendo usados em DTOs e eventos |
| `spring-boot-starter-test`, `spring-security-test` | test | JUnit 5, Mockito, MockMvc |
| `spring-boot-testcontainers`, Testcontainers PostgreSQL e JUnit 5 | test | PostgreSQL real |

Plugins: `spring-boot-maven-plugin` (com `build-info` para `/actuator/info`), `maven-failsafe-plugin` para testes `*IT`.

### 1.2 Layout de diretórios

```
back-java/
├─ pom.xml
├─ Dockerfile
├─ docker-compose.yml
├─ .env.example
└─ src/
   ├─ main/java/crm_data/
   │  ├─ Application.java
   │  ├─ shared/
   │  │  ├─ config/        (SecurityConfig, JwtConfig, CorsConfig, DataSourceConfig, JpaAuditingConfig)
   │  │  ├─ security/      (JwtService, TokenPrincipal, RoleGuard, LoginRateLimiter)
   │  │  ├─ tenant/        (TenantContext, TenantTransactionAspect, TenantFilterEnabler)
   │  │  ├─ crypto/        (AesGcmCipher, KeyRing, EncryptedStringConverter)
   │  │  ├─ error/         (GlobalExceptionHandler, BusinessException, ProblemTypes)
   │  │  ├─ audit/         (AuditableEntity)
   │  │  └─ storage/       (FileStorage – interface, usada na Fase 1)
   │  ├─ auth/             (web, service, domain, repository)
   │  ├─ clinic/           (web, service, domain, repository)
   │  ├─ patient/  record/  scheduling/  billing/  finance/  documents/  dashboard/   (fases 1–4)
   └─ main/resources/
      └─ application.yml, application-dev.yml, application-prod.yml
   └─ test/java/crm_data/ (unitários, @WebMvcTest, *IT com Testcontainers)
```

Regras de dependência entre módulos: um módulo só chama a interface pública do `service` de outro; nunca o `repository` alheio. `shared` não depende de módulos de negócio. Um teste de arquitetura (ArchUnit, opcional) pode impor isso.

---

## 2. Contrato exigido do banco

O backend **não cria nem altera objetos de banco**: `spring.jpa.hibernate.ddl-auto=validate` (sem Flyway no projeto). Tudo abaixo é entregue pelo `postgres-dba-architect`.

### 2.1 Papel de aplicação
- A aplicação conecta com um único papel, `crm_app` (`DB_APP_USER`): **sem `BYPASSRLS`, não dono das tabelas, sem DDL**.
- Todos os objetos ficam no schema `core`: o JPA usa `spring.jpa.properties.hibernate.default_schema=core`.
- O backend não conhece nem usa o papel dono/migrations.

### 2.2 Variável de sessão do tenant
- A cada transação, o backend executa `set_config('app.clinic_id', '<uuid>', true)` (equivale a `SET LOCAL`).
- O banco expõe `core.current_clinic_id()`, que lê essa variável e retorna `NULL` quando ausente; sem tenant, nenhuma linha é visível nem gravável (falha fechada).

### 2.3 Tabelas e colunas esperadas (Fase 0, schema `core`)

Dicionário completo e definitivo: `docs/features/banco/F03-core-tables/contract.md`. Resumo:

| Tabela | Colunas esperadas | Constraints exigidas |
|---|---|---|
| `clinics` | `id uuid`, `name`, `document`, `phone`, `email`, `address`, `created_at`, `updated_at`, `version` | PK `id`; RLS por `id = tenant` |
| `users` | `id uuid`, `clinic_id`, `name`, `email`, `password_hash`, `role` (`DENTIST_ADMIN`/`RECEPTIONIST`), `active`, `created_at`, `updated_at`, `created_by`, `version` | E-mail único global (minúsculas); no máximo um `DENTIST_ADMIN` por clínica; RLS por `clinic_id` |
| `refresh_tokens` | `id uuid`, `clinic_id`, `user_id`, `family_id uuid`, `token_hash varchar(64)`, `expires_at`, `used_at`, `revoked_at`, `created_at` | `token_hash` único; índices por `user_id` e `family_id`; RLS por `clinic_id` |

Convenções (da função banco, aqui só referenciadas): `uuid` como chave, `timestamptz`, `NUMERIC(12,2)` para dinheiro, `clinic_id NOT NULL` indexado em toda tabela de negócio, RLS `ENABLE` + `FORCE`.

### 2.4 Funções de bootstrap (únicas leituras antes de haver tenant)
`SECURITY DEFINER`, com `EXECUTE` concedido só ao papel de aplicação e retorno mínimo:

| Função | Retorno | Usada em |
|---|---|---|
| `core.find_user_for_login(p_email varchar)` | `id, clinic_id, password_hash, role, active` | Login |
| `core.find_refresh_token(p_hash varchar)` | `id, clinic_id, user_id, family_id, expires_at, used_at, revoked_at` | Refresh e logout |

### 2.5 Registro de clínica
O banco deve permitir o fluxo: a aplicação gera o UUID da clínica, define `app.clinic_id` com ele e insere `clinics` e `users` na mesma transação, com a política de `INSERT` validando `id`/`clinic_id` contra o tenant da sessão.

### 2.6 Requisitos de conexão que afetam o backend
- TLS obrigatório na URL de conexão (`sslmode` estrito).
- A API conecta pelo Supavisor em modo *transaction* (porta 6543, usuário `crm_app.<project-ref>`); o driver precisa de `prepareThreshold=0`.
- Migrations e backup **não** passam pelo backend: rodam no GitHub Actions (função banco). O backend não tem `DB_OWNER_*` e não agenda a limpeza de refresh tokens (feita pelo workflow de manutenção do banco via `core.purge_expired_refresh_tokens()`).
- Pool Hikari do backend: máximo 10 conexões; o banco deve suportar isso.
- Região próxima à API (latência por consulta).
- **Provedor (Supabase, plano gratuito) e backups são decisão da função banco**; ver `docs/global/banco/prd.md`.

### 2.7 Testes de integração
O backend roda Testcontainers (PostgreSQL) aplicando os **scripts do banco** em `database/bootstrap` e `database/migrations` (pasta na raiz do repositório, fora de `back-java/`; os testes a referenciam por caminho relativo, por exemplo `../database`). Se o schema divergir do esperado, `ddl-auto=validate` falha o teste.

### 2.8 Estado em memória

- **Stateless:** nenhuma sessão HTTP.
- `TenantContext` (por requisição/thread): `clinicId`, `userId`, `role`, preenchido a partir do JWT.
- Contador de rate limit em memória (Bucket4j). Perde o estado a cada restart e não é compartilhado entre instâncias; aceitável com 1 instância.

---

## 3. Arquitetura de componentes (não há UI; componentes de backend)

| Componente | Responsabilidade |
|---|---|
| `SecurityConfig` | Cadeia stateless: rotas públicas (`/api/v1/auth/**`, `/actuator/health/**`), resto autenticado; CORS; cabeçalhos de segurança; 401/403 em `ProblemDetail` |
| `JwtService` | Emite o access token (HS256, 15 min) e converte claims em `TokenPrincipal` |
| `TenantTransactionAspect` | A cada `@Transactional`, executa `set_config('app.clinic_id', <id>, true)` (equivale a `SET LOCAL`) e habilita o filtro Hibernate `tenantFilter` |
| `AuditableEntity` | Superclasse com `created_at`, `updated_at`, `created_by`, `version` (`@Version`) |
| `AesGcmCipher` / `KeyRing` / `EncryptedStringConverter` | Criptografia de campos (usada a partir da Fase 1) |
| `LoginRateLimiter` | Limita login e registro por IP (e por IP+e-mail no login) |
| `GlobalExceptionHandler` | Converte exceções em `ProblemDetail` (RFC 9457) |
| `FileStorage` (interface) | Armazenamento de arquivos; implementação de produção no Supabase Storage (Fase 1), disco local só em dev/teste |

---

## 4. Lógica central

### 4.1 Registro (`POST /api/v1/auth/register`)
1. Rate limit por IP.
2. Validar payload (Bean Validation); normalizar e-mail para minúsculas.
3. Abrir transação; gerar `clinicId = UUID`; executar `set_config('app.clinic_id', clinicId, true)`.
4. Inserir `clinics` e `users` (`DENTIST_ADMIN`, BCrypt custo 12); a RLS valida `id/clinic_id = app.clinic_id`.
5. Se o e-mail já existir, a constraint `ux_users_email` falha → 409 (sem revelar mais que isso).
6. Emitir access token e criar refresh token (nova `family_id`).
7. Commit e resposta.

### 4.2 Login
1. Rate limit (IP e IP+e-mail).
2. Chamar `core.find_user_for_login(email)`. Se não existir, **ainda assim** executar uma comparação BCrypt com hash fictício (igualar o tempo) e responder 401 genérico.
3. Comparar a senha; se `active = false` ou senha errada → 401 genérico.
4. Emitir access token (`sub`, `clinic_id`, `role`, `iat`, `exp`, `jti`) e novo refresh token em nova `family_id`.

### 4.3 Refresh (rotação)
1. Calcular SHA-256 do token recebido; chamar `core.find_refresh_token`.
2. Token inexistente, expirado ou revogado → 401.
3. Token com `used_at` preenchido (**reuso**) → revogar toda a `family_id` e responder 401.
4. Senão: dentro de transação com o `clinic_id` do token, marcar `used_at`, gerar novo refresh na mesma `family_id` e novo access.
5. Rechecar que o usuário continua `active`; se não, revogar a família.

### 4.4 Logout
Revoga o refresh token informado (e a sua família). Idempotente: token desconhecido também retorna 204.

### 4.5 Gestão de usuários
- Dentista cria **apenas** `RECEPTIONIST` (índice `ux_users_one_dentist` garante um dentista por clínica).
- Não pode desativar a si mesmo. Desativar revoga os refresh tokens do alvo.
- Dentista pode redefinir a senha de um recepcionista (substitui a recuperação por e-mail); revoga os refresh tokens do alvo.
- Senha esquecida do **próprio dentista**: sem fluxo automático (sem e-mail). Procedimento manual de suporte, a documentar no README de operação.

### 4.6 Isolamento de tenant (duas camadas)
1. **Aplicação:** `@Filter` do Hibernate (`clinic_id = :clinicId`) habilitado em toda sessão; `clinic_id` das entidades preenchido pelo `TenantContext`, nunca pela requisição.
2. **Banco:** RLS com `FORCE`, papel sem `BYPASSRLS`, `set_config(..., true)` dentro de cada transação.
Operações sem transação ativa em tabelas de negócio devem falhar (nenhuma linha visível). `SET LOCAL` fora de transação não persiste, evitando vazamento entre conexões do pool.

### 4.7 Criptografia AES-256-GCM
- Formato do valor gravado: `base64( versão[1 byte] | IV[12 bytes] | texto cifrado + tag[16 bytes] )`; IV aleatório novo a cada gravação.
- `KeyRing` lê `CRM_AES_KEYS` (ex.: `1:<base64 de 32 bytes>,2:<…>`) e `CRM_AES_ACTIVE_VERSION`. Gravação usa a chave ativa; leitura escolhe a chave pela versão do byte inicial.
- Rotação: publicar nova versão como ativa, manter as antigas para leitura e rodar uma tarefa de recriptografia gradual; remover a chave antiga só quando nenhuma linha usar a versão.
- Campos cifrados não são pesquisáveis; nome e identificadores de busca do paciente ficam em texto claro (decisão da Fase 1).

### 4.8 Contrato do JWT e do refresh
- **Access:** HS256, segredo ≥ 256 bits em `CRM_JWT_SECRET`, `iss=crm-clinica`, expiração 15 min, claims `sub`, `clinic_id`, `role`, `jti`.
- **Refresh:** 256 bits aleatórios (`SecureRandom`), base64url, validade 7 dias, guardado só como SHA-256.
- **Entrega ao React:** ambos no corpo da resposta JSON. Decisão a revisitar na spec do frontend (armazenamento seguro do refresh no cliente).

---

## 5. Endpoints da Fase 0

Prefixo `/api/v1`. Autenticação: `Bearer <access token>`. Papéis: **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`, **P** = público.

| Método | Rota | Papéis | Request | Response | Erros |
|---|---|---|---|---|---|
| POST | `/auth/register` | P | `{clinicName, name, email, password}` | 201 `{accessToken, refreshToken, expiresIn, user}` | 400, 409, 429 |
| POST | `/auth/login` | P | `{email, password}` | 200 `{accessToken, refreshToken, expiresIn, user}` | 400, 401, 429 |
| POST | `/auth/refresh` | P | `{refreshToken}` | 200 mesmo formato do login | 400, 401, 429 |
| POST | `/auth/logout` | P | `{refreshToken}` | 204 | 400 |
| GET | `/me` | D, R | — | 200 `{id, name, email, role, clinicId}` | 401 |
| PUT | `/me` | D, R | `{name, email}` | 200 perfil | 400, 401, 409 |
| PUT | `/me/password` | D, R | `{currentPassword, newPassword}` | 204 | 400, 401, 422 |
| GET | `/clinic` | D, R | — | 200 `{id, name, document, phone, email, address}` | 401 |
| PUT | `/clinic` | D | `{name, document?, phone?, email?, address?}` | 200 clínica | 400, 403 |
| GET | `/users?page&size` | D | `size` máx. 50 | 200 página de `{id, name, email, role, active}` | 403 |
| POST | `/users` | D | `{name, email, password}` (papel sempre `RECEPTIONIST`) | 201 usuário | 400, 403, 409 |
| PATCH | `/users/{id}/active` | D | `{active}` | 200 usuário | 403, 404, 422 |
| PUT | `/users/{id}/password` | D | `{newPassword}` (só para `RECEPTIONIST`) | 204 | 400, 403, 404 |

**Validações:** `email` válido, até 254 caracteres; `name` 2–120; `clinicName` 2–150; senha 10–64 caracteres (limite de 72 bytes do BCrypt), com ao menos letra e número. Nenhum DTO aceita `clinicId`, `role` (exceto o implícito) ou `id` do cliente em corpo. `{id}` de rota é sempre resolvido **dentro** do tenant: id de outra clínica retorna 404.

**Regras de negócio com 422:** desativar a si mesmo; desativar/alterar o dentista; senha nova igual à atual.

**Contrato de erro (`application/problem+json`):**

```json
{
  "type": "https://crm.local/problems/validation",
  "title": "Dados inválidos",
  "status": 400,
  "detail": "Um ou mais campos são inválidos.",
  "instance": "/api/v1/auth/register",
  "errors": [{ "field": "password", "message": "mínimo de 10 caracteres" }]
}
```

Tipos: `validation` (400), `unauthorized` (401), `forbidden` (403), `not-found` (404), `conflict` (409), `business-rule` (422), `rate-limited` (429, com `Retry-After`), `internal` (500, sem detalhes internos).

---

## 6. Erros e casos de borda

| Cenário | Tratamento |
|---|---|
| Login com e-mail inexistente | 401 genérico, com tempo de resposta igualado ao de e-mail existente |
| Reuso de refresh token | Revoga toda a família, 401; registrar evento de segurança (sem o token) |
| Dois registros simultâneos com o mesmo e-mail | `ux_users_email` garante um vencedor; o outro recebe 409 |
| Requisição de escrita sem tenant definido | RLS bloqueia (0 linhas / violação); handler devolve 500 `internal` e loga; teste cobre |
| `app.clinic_id` vazando entre conexões do pool | Só `set_config(..., true)` dentro de transação; teste de integração com pool de 1 conexão |
| Edição concorrente (clínica/usuário) | `@Version` → 409 `conflict` |
| Falha do banco / timeout | 503 com `ProblemDetail`, sem stack trace |
| Chave AES ausente ou inválida | A aplicação **não sobe** (falha no startup) |
| Segredo JWT curto (< 32 bytes) | A aplicação não sobe |
| Rate limit excedido | 429 com `Retry-After` |
| Bloqueio de 429 em massa por trás do proxy do Render | Resolver o IP real via `X-Forwarded-For` confiável (`server.forward-headers-strategy=native`) |

---

## 7. Segurança e desempenho

**Segurança**
- Spring Security stateless; BCrypt custo 12; CSRF desabilitado (sem cookies de sessão); CORS restrito a `CRM_CORS_ORIGINS`.
- Cabeçalhos: `X-Content-Type-Options`, `Strict-Transport-Security` (prod), `X-Frame-Options: DENY`, `Cache-Control: no-store` nas rotas de auth.
- Autorização por rota em `SecurityConfig` **e** `@PreAuthorize` nos services, como segunda barreira.
- Logs: sem senha, token, hash ou conteúdo clínico; correlação por `X-Request-Id` (MDC).
- OpenAPI e endpoints do Actuator (exceto `health`) desligados em `prod`.
- Sem concatenação de SQL: JPA/JPQL parametrizados; SQL nativo só nas funções de bootstrap, com parâmetros.

**Desempenho (metas do PRD)**
- Login p95 ≤ 300 ms com até 4 concorrentes (custo 12 do BCrypt consome ~245 ms de CPU por tentativa; medido: 4 concorrentes p95 295 ms; 50 concorrentes ~1,5 s, aceito). Revalidar na máquina do Render.
- Consultas por `id` e `email` usam índices únicos; paginação com `size` máx. 50.
- Pool Hikari pequeno (máx. 10) para respeitar o limite de conexões do plano gratuito do PostgreSQL.

---

## 8. Configuração, Docker e Render

**Perfis:** `dev` (springdoc ligado, logs verbosos), `test` (Testcontainers), `prod` (tudo desligado, `ddl-auto=validate`).

**Variáveis de ambiente (`.env.example` documentado, nenhum valor real no repositório):**

| Variável | Uso |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` ou `prod` |
| `PORT` | Porta (`server.port=${PORT:8080}`) |
| `DB_URL`, `DB_APP_USER`, `DB_APP_PASSWORD` | Datasource da aplicação (papel de aplicação do banco) |
| `CRM_JWT_SECRET` | Assinatura do access token |
| `CRM_AES_KEYS`, `CRM_AES_ACTIVE_VERSION` | Chaveiro AES |
| `CRM_CORS_ORIGINS` | Origens do React, separadas por vírgula |
| `CRM_STORAGE_URL` | URL do Supabase Storage (Fase 1) |
| `CRM_STORAGE_SERVICE_KEY` | Service key do Supabase; apenas no backend, nunca no front nem no repositório (Fase 1) |
| `CRM_STORAGE_BUCKET` | Bucket **privado** dos exames (Fase 1) |

**Dockerfile (multi-stage):** estágio de build com Maven + JDK 21; runtime em JRE 21 enxuto; usuário não-root; `HEALTHCHECK` em `/actuator/health/liveness` (o health check do Render usa `/actuator/health`); `JAVA_TOOL_OPTIONS` com limite de memória compatível com o plano.

**docker-compose (dev):** serviços `app` e `postgres:16` com volume e healthcheck; o Postgres local aplica `database/bootstrap` e `database/migrations` (da função banco) na subida; variáveis lidas de `.env`.

**Render:** apenas o serviço Web (Docker) da API; health check path `/actuator/health`; `server.forward-headers-strategy=native`. O PostgreSQL é um deploy separado no Supabase, de responsabilidade da função banco (ver seção 2.6): `DB_URL` aponta para ele e o `docker-compose` de dev continua com um Postgres local. Como API e banco ficam em redes distintas, exigir TLS na URL (`sslmode=require` ou mais estrito) e liberar no banco apenas o acesso vindo da API, quando o provedor permitir restrição por IP.
> **Arquivos de exame (decisão 2026-10-21):** o disco do container no Render é efêmero, por isso os exames **não** ficam nele. Ficam no **Supabase Storage**, em bucket privado, atrás da interface `FileStorage` (implementação `SupabaseFileStorage`, via API REST do Storage).
> - A chave do objeto é sempre `<clinic_id>/<paciente_id>/<arquivo>`, montada só a partir do `TenantContext`. A service key ignora as políticas do Storage, então o isolamento entre clínicas é responsabilidade do backend; a RLS do banco não protege os arquivos.
> - Download por URL assinada de curta duração ou por proxy do backend; nunca bucket público.
> - Cifrar o conteúdo com AES-GCM (`AesGcmCipher`, F03) no backend antes do upload; a decisão final de cifrar arquivos é fechada no spec da Fase 1.
> - Em dev e teste, uma implementação em disco local (ou em memória) cumpre a mesma interface.

---

## 9. Estratégia de testes (para o `dev-engineer-java`)

| Camada | Ferramenta | Casos críticos |
|---|---|---|
| Unitário | JUnit 5 + Mockito | Regras de `auth` e `clinic`: rotação de refresh, reuso, bloqueio de desativar a si mesmo, política de senha, `AesGcmCipher` (round-trip, IV distinto, adulteração detectada, versão de chave) |
| Web | `@WebMvcTest` + `spring-security-test` | Matriz papel × endpoint (401/403/200), formato `ProblemDetail`, DTOs que ignoram `clinicId` |
| Integração | Testcontainers PostgreSQL com os scripts do banco | Scripts do banco aplicam do zero; **dois tenants com o filtro Hibernate desligado: a RLS sozinha impede a leitura cruzada**; papel de aplicação sem `BYPASSRLS`; pool de 1 conexão sem vazamento de `app.clinic_id`; registro concorrente com o mesmo e-mail; login com função `SECURITY DEFINER` |
| Segurança | MockMvc/IT | Rate limit retorna 429; token expirado e adulterado retornam 401; usuário desativado não faz login nem refresh |

Critérios de saída da Fase 0: `mvn verify` verde em máquina limpa, cobertura ≥ 80% nos services de `auth`, e os itens de aceite US-001 a US-007 do PRD atendidos.

---

## 10. Pontos a validar antes de codar

1. Validar contra o Supabase real o que a função banco listou na seção 13 de `docs/global/banco/spec.md` (papéis, usuário do Supavisor, `pg_dump`). Nada pendente do lado do backend.
2. Nomes exatos dos starters e versão do springdoc/Bucket4j/Testcontainers compatíveis com Boot 4.1.
3. Armazenamento do refresh token no React (será tratado na spec do frontend).
