# Technical Specification (Spec)

## Spec: Deploy, Ajustes de Infraestrutura e Unificação Frontend (Vercel) + Backend (Render)

**ID:** `SPEC-DEPLOY-UNIFICACAO-001`  
**PRD de Referência:** [tasks/prd-deploy-render-vercel.md](file:///C:/Users/progc/Daniel/dental-clinic-crm/tasks/prd-deploy-render-vercel.md)  
**Status:** Aprovado  
**Data:** 2026-07-24  
**Repositórios / Diretórios:** `backend-go/` e `frontend_flutter/`  

---

### 1. Technical Overview

- **Funcionalidade/Recurso:** Deploy, Ajustes de Infraestrutura, Segurança CORS e Unificação E2E do Dental Clinic CRM.
- **Tech Stack Utilizada:**
  - **Backend:** Go 1.26, Fiber v2, GORM, Supabase Storage API Client, godotenv.
  - **Frontend:** Dart 3.12.2, Flutter Web, Riverpod, Dio HTTP Client, `go_router`.
  - **Infraestrutura Cloud:** Render Web Service (`https://crm-clinica-gjss.onrender.com`), Vercel Static/SPA Hosting (`https://crm-clinica-ten.vercel.app`), Supabase Cloud (PostgreSQL via Session Mode Pooler `:5432` + Object Storage).
- **Abordagem Arquitetural:** Desacoplada SPA Client / REST API. O frontend Flutter Web é estaticamente compilado na Vercel e consome a API RESTful em Go hospedada no Render via chamadas HTTP/HTTPS autenticadas por JWT.

---

### 2. Data Models, Environment & Configuration Schema

#### 2.1 Backend Environment Variables Schema (`backend-go/.env` & Render Dashboard)

| Variável | Tipo | Obrigatório | Descrição / Valor Padrão Exemplo |
| :--- | :--- | :--- | :--- |
| `PORT` | `string` | Sim | `8080` (Injetada automaticamente pelo Render) |
| `DATABASE_URL` | `string` | Sim | `postgresql://postgres.hecpazxguibzkcsibpjq:SENHA@aws-0-us-east-1.pooler.supabase.com:5432/postgres` (Usar sempre o **Pooler Supavisor** IPv4) |
| `JWT_SECRET` | `string` | Sim | Chave secreta de alta entropia para assinatura de tokens JWT |
| `SUPABASE_URL` | `string` | Sim | `https://hecpazxguibzkcsibpjq.supabase.co` |
| `SUPABASE_KEY` | `string` | Sim | Chave `anon` ou `service_role` para integração com Supabase Storage |
| `SUPABASE_STORAGE_BUCKET` | `string` | Sim | `clinic-files` (Bucket para arquivos de pacientes e avatares) |
| `ALLOWED_ORIGINS` | `string` | Sim | `https://crm-clinica-ten.vercel.app,http://localhost:*,https://*.vercel.app` |

#### 2.2 Frontend Environment & Build Config (`frontend_flutter`)

| Configuração | Arquivo | Valor / Formato |
| :--- | :--- | :--- |
| `API_BASE_URL` | `build.sh` | `--dart-define=API_BASE_URL=https://crm-clinica-gjss.onrender.com` |
| Route Rewrite | `vercel.json` | `{"source": "/(.*)", "destination": "/index.html"}` |

---

### 3. Component Architecture & Endpoints

#### 3.1 Alterações no Backend (`backend-go`)

1. **Configuração Dinâmica de CORS (`cmd/server/main.go` ou `internal/middleware/cors.go`)**
   - Substituir a configuração permissiva estática (`AllowOrigins: "*"`) por leitura de `ALLOWED_ORIGINS` da variável de ambiente com suporte a fallbacks seguros.
   - Habilitar `AllowCredentials: true`, `AllowHeaders: "Origin, Content-Type, Accept, Authorization, X-Requested-With"`, `AllowMethods: "GET, POST, PUT, DELETE, OPTIONS, PATCH"`.
   - Adicionar `MaxAge: 86400` (24h de cache para preflight OPTIONS).

2. **Endpoint de Health Check (`internal/adapters/handlers/health_handler.go` & `routes.go`)**
   - **Rota:** `GET /healthz` e `GET /health` (Pública, sem middleware de auth).
   - **Lógica:**
     ```go
     type HealthResponse struct {
         Status    string    `json:"status"`
         Database  string    `json:"database"`
         Timestamp time.Time `json:"timestamp"`
         Version   string    `json:"version"`
     }
     ```
     - Executar `sqlDB, err := database.DB.DB(); err.Ping()` para testar a saúde do Pooler do Supabase.
     - Retornar HTTP 200 OK caso `err == nil`. Caso contrário, retornar HTTP 503 Service Unavailable.

3. **Adaptador de Upload para Supabase Storage (`internal/pkg/storage/supabase.go`)**
   - Reemplaçar escrita direta no diretório de arquivos estáticos local (`./uploads/`) por chamadas de API do Supabase Storage.
   - Upload de arquivos via POST REST Multipart para `SUPABASE_URL/storage/v1/object/bucket/file_path`.
   - Retorno da URL pública (`SUPABASE_URL/storage/v1/object/public/bucket/file_path`).

#### 3.2 Alterações no Frontend (`frontend_flutter`)

1. **Script de Build da Vercel (`build.sh`)**
   - Atualizar a instrução de compilação web para:
     ```bash
     flutter build web --release --dart-define=API_BASE_URL=https://crm-clinica-gjss.onrender.com
     ```

2. **Cliente HTTP Dio (`lib/core/network/api_client.dart`)**
   - Garantir sanitização adequada da URL recebida via `String.fromEnvironment('API_BASE_URL')`.
   - Adicionar tratamento de exceção específico no `DioException` para timeout (quando o Render estiver em Cold Start), oferecendo feedback intuitivo ao usuário.

---

### 4. Core Logic & Algorithms

#### 4.1 Algoritmo de CORS e Sanitização de Origem (Backend)
```mermaid
sequenceDiagram
    autonumber
    Client (Vercel)-->>Backend (Render): Preflight OPTIONS /api/v1/auth/login (Origin: crm-clinica-ten.vercel.app)
    Backend (Render)->>Backend (Render): Checa se Origin bate com ALLOWED_ORIGINS (.env)
    alt Origem Permitida
        Backend (Render)-->>Client (Vercel): 204 No Content (Access-Control-Allow-Origin: crm-clinica-ten.vercel.app, Credentials: true)
        Client (Vercel)-->>Backend (Render): POST /api/v1/auth/login
        Backend (Render)-->>Client (Vercel): 200 OK + JWT Token
    else Origem Não Permitida
        Backend (Render)-->>Client (Vercel): 403 Forbidden (Origin not allowed)
    end
```

#### 4.2 Lógica do Endpoint `/healthz`
1. O servidor recebe requisição `GET /healthz`.
2. O handler obtém a conexão SQL subjacente (`database.DB.DB()`).
3. Dispara um contexto com timeout de 3 segundos executando `sqlDB.PingContext(ctx)`.
4. Se o banco responder, monta JSON com `status: "ok"`, `database: "connected"`, timestamp atual e HTTP Status 200.
5. Se o banco falhar ou expirar o timeout, monta JSON com `status: "error"`, `database: "disconnected"`, erro e HTTP Status 503.

---

### 5. Error Handling & Edge Cases

| Cenário de Erro | Causador / Sintoma | Tratamento & Mitigação |
| :--- | :--- | :--- |
| **Render Cold Start Delay** | O serviço gratuito do Render entra em sleep após 15 min. A requisição de login expira o timeout padrão do Dio (15s). | 1. Aumentar `connectTimeout` no Dio para 30s.<br>2. Adicionar mensagem no UI: *"Servidor iniciando... por favor aguarde alguns segundos"*. |
| **CORS Blocked (Preflight)** | Origem do front enviando porta ou subdomínio dinâmico da Vercel (ex: `preview.vercel.app`). | O middleware de CORS em Go deve aceitar regex wildcard para subdomínios da Vercel (`https://.*\.vercel\.app`). |
| **Supabase IPv6 Connection Failure** | Tentativa de conexão via hostname direto `db.[ref].supabase.co` falha no Render. | Código de inicialização em `database.go` valida se a URL do banco contém `.pooler.supabase.com`. Emitir aviso no log se estiver errada. |
| **Vercel Direct Sub-route Refresh (404)** | Usuário atualiza a página na URL `crm-clinica-ten.vercel.app/patients/123`. | O `vercel.json` garante o rewrite `/(.*) -> /index.html`, repassando a rota client-side para o `go_router`. |

---

### 6. Security & Performance

- **Segurança CORS:** O backend nunca deve usar `AllowOrigins: "*"` junto com `AllowCredentials: true`. A lista de origens deve ser estritamente validada.
- **Sanitização de DSN:** A função `cleanDSN` deve continuar removendo aspas duplas, quebras de linha (`\n`, `\r`) e espaços em branco das variáveis de ambiente copiadas para o Render.
- **Cache de Preflight OPTIONS:** Definir `MaxAge: 86400` para reduzir o overhead de chamadas preflight do navegador para o Render.
- **Performance de Build:** O clone do Flutter SDK no `build.sh` continuará usando `--depth 1` para minimizar tempo de compilação na Vercel (< 3 minutos).

---

### 7. Verificação e Testes

- **Lint / Typecheck Backend:** `cd backend-go && go vet ./...`
- **Lint / Typecheck Frontend:** `cd frontend_flutter && flutter analyze`
- **Validação de Build Web Local:** `cd frontend_flutter && flutter build web --release --dart-define=API_BASE_URL=https://crm-clinica-gjss.onrender.com`
