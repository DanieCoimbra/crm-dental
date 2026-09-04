# Especificação Técnica Global de Backend Go — SaaS Dental CRM

**Documento:** Especificação Técnica Global de Backend Go (Go 1.26 / Fiber / GORM / Supabase)  
**PRD de Origem:** `docs/global/prd.md`  
**Projeto:** SaaS Dental Clinic CRM (Multi-Tenant)  
**Versão:** 2.0 (Atualizado para Go 1.26 + Render Native + Supabase Native Migrations)  

---

## 1. Visão Geral da Arquitetura de Software

- **Linguagem & Framework:** Go 1.26 com **Go Fiber v2**
- **ORM & Banco de Dados:** **GORM v2** conectado ao Supabase PostgreSQL (via Supavisor Connection Pooler na porta 5432)
- **Migrações de Banco:** Versionadas em `/supabase/migrations` (chaves primárias `BIGINT` geradas nativamente)
- **Padrão Arquitetural:** **Clean Architecture** (Adapters, Core Services, Domain Entities, Repositories)
- **Segurança & Tenant:** Autenticação JWT (`golang-jwt/v5`) com extração de `clinic_id` e RBAC (`owner`, `manager`, `doctor`, `receptionist`)

---

## 2. Layout de Diretórios (`backend-go`)

```
backend-go/
├── cmd/
│   └── server/
│       └── main.go                  # Ponto de entrada do servidor Go Fiber
├── internal/
│   ├── adapters/
│   │   ├── handlers/                # HTTP Handlers (Controllers)
│   │   │   ├── auth_handler.go
│   │   │   ├── patient_handler.go
│   │   │   ├── appointment_handler.go
│   │   │   ├── financial_handler.go
│   │   │   └── dashboard_handler.go
│   │   ├── repositories/            # Implementações GORM de Repositório
│   │   └── routes/                  # Mapeamento de rotas e rotas protegidas
│   ├── core/
│   │   ├── domain/                  # Structs de Domínio GORM (ID uint)
│   │   └── services/                # Regras de Negócio e Casos de Uso
│   ├── middleware/
│   │   ├── auth_middleware.go       # Extração e validação do JWT Bearer
│   │   ├── role_middleware.go       # RBAC Middleware
│   │   └── saas_middleware.go       # Interceptor de assinaturas SaaS
│   └── database/
│       └── database.go              # Conexão GORM e AutoMigrate
├── go.mod
└── go.sum
```

---

## 3. Especificação dos Middlewares & Segurança

### 3.1 `RequireAuth`
Valida o cabeçalho `Authorization: Bearer <token>` extraindo `user_id`, `clinic_id` e `role` para `c.Locals()`.

### 3.2 `RequireRole(allowedRoles ...string)`
Filtra acessos restritos garantindo permissão por perfil de usuário.

---

## 4. Estratégia de Deploy no Render (`render.yaml`)

Compilação e execução nativa sem dependência de contêineres Docker:

```yaml
services:
  - type: web
    name: dental-crm-api
    env: go
    rootDir: backend-go
    buildCommand: go build -o server cmd/server/main.go
    startCommand: ./server
    plan: free
    region: oregon
    healthCheckPath: /health
```
