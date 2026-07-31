# PRD de Backend Go — Adequação à Fase 0 & Supabase Security

**Documento:** Product Requirements Document (PRD) de Backend Go  
**Projeto:** SaaS Clínica Dental Solo (CRM Clínica Dental) — Backend API (`backend-go`)  
**Autor:** Go Architect  
**Versão:** 1.0  
**Status:** Em Revisão / Planejamento  

---

## 1. Resumo Executivo & Declaração do Problema

### 1.1 Problema
Atualmente, o backend em Go (`backend-go`) precisa ser ajustado e alinhado com a **Fase 0 — Fundação** (`docs/Fase-0.md`) e com as diretrizes de cibersegurança e banco de dados estabelecidas pelo agente `supabase_sec_dba` (`docs/global/prd.md` e `docs/global/spec.md`).

Alguns handlers e middlewares legados do backend mantêm referências a lógicas antigas ou não propagam adequadamente as claims do JWT (`clinic_id` e `role`) para as sessões do PostgreSQL/GORM, correndo o risco de bypass das políticas RLS configuradas no Supabase.

### 1.2 Solução Proposta
Refatorar e alinhar a arquitetura do `backend-go` para:
1. **Injeção de Claims do JWT:** Garantir que os middlewares de Autenticação e Tenant extraiam e validem `clinic_id` e `role` do token Supabase JWT em todas as requisições protegidas.
2. **Propagação de Contexto no GORM:** Configurar as conexões/transações do GORM para transmitir a sessão autenticada com as claims do tenant para o PostgreSQL, garantindo a execução transparente das políticas RLS.
3. **Adequação de Endpoints (Clean Architecture):** Simplificar e expor exclusivamente os endpoints necessários para a Fase 0:
   - `POST /api/v1/auth/register-clinic` (Registro atômico de Clínica + Admin com 14 dias de Trial).
   - `POST /api/v1/auth/login` (Autenticação e geração/retorno do JWT Supabase).
   - `GET /api/v1/profile` (Dados do perfil do usuário e clínica vinculada).
   - `GET /api/v1/health` (Healthcheck para infraestrutura/Docker).
4. **Remoção/Desativação de Rotas Fora de Escopo:** Limpar ou isolar rotas do ecossistema antigo que não pertencem ao modelo de clínica 1 consultório / dentista solo.

### 1.3 Objetivos e Métricas de Sucesso
- **[GO-01] Cobertura de Tenant Context:** 100% dos handlers privados possuem acesso garantido a `clinic_id` e `role` via `c.Locals()` do Go Fiber / Context.
- **[GO-02] Fidelidade ao Supabase RLS:** Nenhuma chamada ao banco de dados utiliza a `service_role` para bypass de RLS, exceto na rotina interna de provisionamento atômico de registro.
- **[GO-03] Performance e Resiliência:** Resposta das APIs de Auth/Perfil em menos de 100ms e inicialização da aplicação conteinerizada via Docker em menos de 5 segundos.

---

## 2. Histórias de Usuário (User Stories)

### US-GO-001: Autenticação e Extração de Claims JWT
**Descrição:** Como usuário do aplicativo (Dentista ou Recepcionista), quero autenticar no backend Go para receber um token válido e ter minhas requisições validadas com o isolamento da minha clínica.

**Critérios de Aceite:**
- [ ] Middleware `AuthMiddleware` valida a assinatura e expiração do JWT emitido pelo Supabase.
- [ ] Middleware `TenantMiddleware` extrai `clinic_id` e `role` do payload e disponibiliza no contexto da requisição Fiber.
- [ ] Requisições com tokens inválidos ou ausentes retornam status HTTP 401 (`Unauthorized`).

### US-GO-002: Registro Atômico de Clínica & Trial de 14 Dias
**Descrição:** Como um novo Dentista Solo, quero cadastrar minha clínica e meu usuário administrador através do backend Go em um único passo.

**Critérios de Aceite:**
- [ ] Endpoint `POST /api/v1/auth/register-clinic` recebe os dados da clínica e do administrador.
- [ ] O backend executa uma transação atômica criando o registro em `clinics` (`trial_ends_at = NOW() + 14 dias`) e em `users` (`role = 'admin'`).
- [ ] Retorna status HTTP 201 (`Created`) com o token JWT e dados iniciais da sessão.

### US-GO-003: Proteção de Rotas com RBAC (Admin vs Recepcionista)
**Descrição:** Como Administrador do sistema, quero que rotas restritas recebam bloqueio do backend Go caso a Recepcionista tente acessá-las.

**Critérios de Aceite:**
- [ ] Middleware `RoleGuard("admin")` intercepta tentativas de acesso da role `receptionist` a endpoints restritos.
- [ ] Retorna status HTTP 403 (`Forbidden`) com mensagem padronizada em JSON.

---

## 3. Requisitos Funcionais do Backend Go

- **FR-GO-1:** O backend deve ser estruturado em **Clean Architecture** (Adapters/Handlers, Core/UseCases, Core/Domain, Database/Repositories).
- **FR-GO-2:** O roteador HTTP deve utilizar **Go Fiber v2**, mantendo alta performance e facilidade de middlewares.
- **FR-GO-3:** O ORM deve utilizar **GORM v2** configurado com driver PostgreSQL Supabase.
- **FR-GO-4:** As respostas de erro devem seguir o padrão padronizado em JSON:
  ```json
  {
    "error": "CÓDIGO_DO_ERRO",
    "message": "Descrição legível do erro",
    "details": null
  }
  ```
- **FR-GO-5:** O projeto deve conter um `Dockerfile` otimizado em multi-stage build produzindo um binário enxuto baseado em Alpine ou Scratch.

---

## 4. Não-Objetivos (Fora do Escopo da Fase 0)

- **Endpoints de Múltiplos Consultórios/Salas:** Removidos/desativados.
- **Endpoints de RBAC Customizável:** Apenas validação estática de `admin` e `receptionist`.
- **Fila de Trabalhos Assíncronos / RabbitMQ:** Não será incluído na Fase 0.

---

## 5. Arquitetura de Infraestrutura e Docker

### 5.1 Dockerfile Multi-Stage Proposto
```dockerfile
# Stage 1: Build
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o server ./cmd/api/main.go

# Stage 2: Runner
FROM alpine:3.19
WORKDIR /app
RUN apk --no-cache add ca-certificates tzdata
COPY --from=builder /app/server .
COPY --from=builder /app/.env .env
EXPOSE 8080
CMD ["./server"]
```

---

## 6. Riscos & Mitigações

- **Risco:** Incompatibilidade entre DDL do Supabase e structs do GORM.
  - *Mitigação:* Usar GORM em modo de mapeamento explícito sem auto-migration destrutiva (desativar `AutoMigrate` automático em produção).
- **Risco:** Latência de conexão no Supabase Pooler (Transaction Mode).
  - *Mitigação:* Configurar o GORM para utilizar `PreparedStmt: false` se conectado via Supabase Transaction Pooler (porta 6543).

---

## 7. Próximos Passos (Workflow Go Architect)

De acordo com as regras inegociáveis do **Go Architect**, o planejamento deve avançar sequencialmente com **aprovação humana entre cada etapa**:

1. **[ATUAL] Etapa 1:** Aprovação deste PRD Global de Backend Go (`docs/global/prd.md`).
2. **Etapa 2:** Elaboração da Especificação Técnica Global do Backend (`docs/global/spec.md`).
3. **Etapa 3:** Breakdown das *features* de Backend Go em domínios isolados (`docs/features/...`).
4. **Etapa 4:** Especificação técnica detalhada por *feature*.
5. **Etapa 5:** Definição dos Contratos de Endpoints e APIs (`contract.md`).
