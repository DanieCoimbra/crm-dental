# Especificação Técnica Global de Backend Go — Fase 0

**Documento:** Especificação Técnica Global de Backend Go (Go / Fiber / GORM / Supabase)  
**PRD de Origem:** `docs/global/prd.md`  
**Projeto:** SaaS Clínica Dental Solo (CRM Clínica Dental)  
**Versão:** 1.0  

---

## 1. Visão Geral da Arquitetura de Software

- **Linguagem & Framework:** Go 1.22+ com **Go Fiber v2**
- **ORM & Banco de Dados:** **GORM v2** conectado ao PostgreSQL / Supabase
- **Padrão Arquitetural:** **Clean Architecture** (Ports and Adapters / Hexagonal)
- **Modelagem de Comunicação:** API RESTful JSON
- **Segurança de Autenticação:** Validação de JWT Supabase + Injeção de Contexto de Tenant (`clinic_id`) e Papel (`role`)

---

## 2. Layout de Diretórios Proposto (`backend-go`)

```
backend-go/
├── cmd/
│   └── api/
│       └── main.go                  # Ponto de entrada da aplicação Go
├── internal/
│   ├── adapters/
│   │   ├── handlers/                # HTTP Handlers (Fiber Context)
│   │   │   ├── auth_handler.go      # Login, Registro de Clínica
│   │   │   ├── profile_handler.go   # Perfil do Usuário
│   │   │   └── health_handler.go    # Healthcheck da aplicação
│   │   └── repositories/            # Implementações GORM de Repositório
│   │       ├── clinic_repository.go
│   │       └── user_repository.go
│   ├── core/
│   │   ├── domain/                  # Entidades de Domínio puras
│   │   │   ├── clinic.go
│   │   │   └── user.go
│   │   ├── ports/                   # Interfaces (Contracts internos)
│   │   │   ├── repository_ports.go
│   │   │   └── usecase_ports.go
│   │   └── usecases/                # Casos de Uso (Regras de Negócio)
│   │       ├── auth_usecase.go
│   │       └── profile_usecase.go
│   ├── middleware/
│   │   ├── auth_middleware.go       # Validação do JWT Supabase
│   │   ├── tenant_middleware.go     # Extração de clinic_id e role
│   │   └── role_guard.go            # Guarda RBAC (admin vs receptionist)
│   └── database/
│       └── postgres.go              # Conexão GORM e Pooler Supabase
├── Dockerfile                       # Multi-stage Docker build
├── docker-compose.yml               # Ambiente local de dev/teste
├── go.mod
└── go.sum
```

---

## 3. Especificação dos Middlewares & Segurança

### 3.1 `AuthMiddleware`
Valida o cabeçalho `Authorization: Bearer <token>` utilizando a chave pública/Secret do Supabase JWT.

### 3.2 `TenantMiddleware`
Extrai do token validado:
- `clinic_id`: Armazena em `c.Locals("clinic_id")`
- `user_id`: Armazena em `c.Locals("user_id")`
- `role`: Armazena em `c.Locals("role")`

### 3.3 `RoleGuardMiddleware(allowedRoles ...string)`
Intercepta a requisição verificando se a role presente em `c.Locals("role")` corresponde às permissões exigidas pela rota. Se não corresponder, interrompe a requisição com HTTP 403.

---

## 4. Endpoints Globais da Fase 0

| Método | Endpoint | Acesso | Descrição |
|---|---|---|---|
| `GET` | `/api/v1/health` | Público | Healthcheck da API e conexão DB |
| `POST` | `/api/v1/auth/register-clinic` | Público | Registro de Clínica (Trial 14 dias) + Admin |
| `POST` | `/api/v1/auth/login` | Público | Autenticação e geração de JWT |
| `GET` | `/api/v1/profile` | Autenticado | Retorna dados do usuário e clínica conectada |

---

## 5. Estratégia de Conteinerização (Docker & Docker-Compose)

### 5.1 Docker-Compose Local (`backend-go/docker-compose.yml`)
```yaml
version: '3.8'
services:
  api:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      - PORT=8080
      - DB_HOST=postgres
      - DB_PORT=5432
      - DB_USER=postgres
      - DB_PASSWORD=postgres
      - DB_NAME=dental_crm
      - JWT_SECRET=super-secret-jwt-key
    depends_on:
      - postgres

  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
      POSTGRES_DB: dental_crm
    ports:
      - "5432:5432"
```

---

## 6. Próximos Passos (Workflow Go Architect)

1. **[CONCLUÍDO] Etapa 1:** PRD Global de Backend Go (`docs/global/prd.md`).
2. **[ATUAL] Etapa 2:** Aprovação desta Especificação Técnica Global (`docs/global/spec.md`).
3. **Etapa 3:** Breakdown em domínios de Backend Go (`docs/features/...`).
4. **Etapa 4:** Especificação técnica detalhada por domínio.
5. **Etapa 5:** Definição dos Contratos de Endpoints (APIs).
