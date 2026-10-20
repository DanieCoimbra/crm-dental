# PRD — F01 Platform Foundation

**Derivação:** PRD global (`docs/global/backend/prd.md`), seções 5 (FR-9, FR-11, FR-13 a FR-16) e 8.
**Dependências externas:** nenhuma. É a base de todas as outras features.

## 1. Introdução

- **Problema:** o `back-java/` é um esqueleto sem web, banco, segurança base, tratamento de erros nem empacotamento. Nenhuma feature de negócio pode ser construída sobre ele.
- **Solução:** preparar o projeto Maven, a configuração por perfis, o contrato de erros, o health check, o CORS, os cabeçalhos de segurança e o empacotamento Docker.
- **Metas:**
  1. `mvn verify` passa em máquina limpa, sem repositórios de snapshot.
  2. A imagem Docker sobe e responde `UP` em `/actuator/health` em até 60 s.
  3. 100% das respostas de erro seguem `application/problem+json`, sem stack trace.
  4. Nenhum segredo versionado; todas as variáveis constam no `.env.example`.

## 2. User stories

### US-001: Projeto compila e testa em máquina limpa
**Descrição:** Como desenvolvedor, quero um `pom.xml` correto para compilar e testar sem ajustes manuais.

**Critérios de aceite:**
- [ ] Parent `spring-boot-starter-parent 4.1.1`; sem `<repositories>` de snapshots; `artifactId` `crm-api`; Java 21.
- [ ] Dependências da Fase 0 conforme a seção 1.1 do spec global.
- [ ] `mvn verify` termina com sucesso.

### US-002: Configuração por perfil e ambiente
**Descrição:** Como operador, quero configurar a aplicação só por variáveis de ambiente.

**Critérios de aceite:**
- [ ] Perfis `dev`, `test` e `prod`; `ddl-auto=validate`.
- [ ] Faltando variável obrigatória em `prod`, a aplicação não sobe e informa qual.
- [ ] `.env.example` documenta todas as variáveis; nenhum valor real no repositório.

### US-003: Erros padronizados
**Descrição:** Como front-end, quero um único formato de erro para tratar todas as falhas.

**Critérios de aceite:**
- [ ] Validação, 401, 403, 404, 409, 422, 429, 503 e 500 retornam `ProblemDetail` (RFC 9457).
- [ ] Erro de validação traz `errors[]` com `field` e `message`.
- [ ] Nenhuma resposta contém stack trace ou mensagem de exceção interna.

### US-004: Operação e observabilidade
**Descrição:** Como operador, quero health check e rastreio por requisição.

**Critérios de aceite:**
- [ ] `/actuator/health/liveness` e `/readiness` públicos; demais endpoints do Actuator indisponíveis em `prod`.
- [ ] Toda resposta traz `X-Request-Id`; o id aparece nos logs (MDC).
- [ ] OpenAPI só disponível no perfil `dev`.

### US-005: Empacotamento
**Descrição:** Como operador, quero uma imagem Docker enxuta e um ambiente local reproduzível.

**Critérios de aceite:**
- [ ] `Dockerfile` multi-stage, usuário não-root, `HEALTHCHECK`.
- [ ] `docker-compose.yml` sobe API e PostgreSQL com volume e healthcheck, aplicando `database/bootstrap` e `database/migrations`.
- [ ] CORS restrito a `CRM_CORS_ORIGINS`; cabeçalhos de segurança aplicados.

## 3. Requisitos funcionais

- **FR-1:** O projeto deve compilar com Java 21 e Spring Boot 4.1.1 estável.
- **FR-2:** A aplicação deve ler toda configuração sensível de variáveis de ambiente e falhar no startup se faltar alguma obrigatória.
- **FR-3:** Toda exceção deve ser convertida em `ProblemDetail` por um handler global.
- **FR-4:** A aplicação deve gerar/propagar `X-Request-Id` e incluí-lo nos logs.
- **FR-5:** A aplicação deve aplicar CORS restrito e os cabeçalhos de segurança definidos no spec global (seção 7).
- **FR-6:** A aplicação deve respeitar `PORT` e `server.forward-headers-strategy=native`.
- **FR-7:** Deve existir a interface `FileStorage` (sem implementação de negócio) em `shared/storage`, para a Fase 1.

## 4. Fora do escopo

- Qualquer regra de negócio, autenticação, tabelas e RLS (F02, F04).
- Pipeline de CI/CD e infraestrutura do banco em nuvem.
- Implementação de armazenamento de arquivos (Fase 1).

## 5. Considerações técnicas e riscos

- Nomes dos starters do Boot 4 e versões de springdoc/Bucket4j/Testcontainers devem ser validados no primeiro build.
- Logs não podem conter senha, token ou conteúdo clínico.
- **Pergunta aberta:** nenhuma.
