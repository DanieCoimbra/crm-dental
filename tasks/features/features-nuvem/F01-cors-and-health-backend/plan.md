# Action Plan: F01 - CORS & Health Check Backend

## 1. Local Scope
- **Derivation**: Extraído do PRD [tasks/prd-deploy-render-vercel.md](file:///C:/Users/progc/Daniel/dental-clinic-crm/tasks/prd-deploy-render-vercel.md) e Spec [tasks/spec-deploy-render-vercel.md](file:///C:/Users/progc/Daniel/dental-clinic-crm/tasks/spec-deploy-render-vercel.md).
- **Responsibilidade**: Ajustar as origens permitidas no middleware CORS do Go (Fiber v2) lendo `ALLOWED_ORIGINS` e implementar a rota pública de monitoramento `GET /healthz` com ping de banco no Supabase Pooler.

## 2. External Dependencies (Before starting)
- Variáveis de ambiente configuradas no Render (`ALLOWED_ORIGINS` contendo `https://crm-clinica-ten.vercel.app`, `https://*.vercel.app`, `http://localhost:*`).
- Conexão válida com o Supabase via Pooler (`DATABASE_URL` no port 5432).

## 3. Execution Phases
- **Phase 1: Configuração do Middleware de CORS**
  - Atualizar `backend-go/cmd/server/main.go` ou `backend-go/internal/middleware/cors.go` para ler a variável `ALLOWED_ORIGINS`.
  - Configurar `AllowCredentials: true`, `AllowHeaders`, `AllowMethods` e `MaxAge: 86400`.
- **Phase 2: Rota e Handler de Health Check (`GET /healthz`)**
  - Criar o handler de health check com ping no banco SQL via GORM com timeout de 3s.
  - Registrar as rotas `/healthz` e `/health` públicas sem autenticação.
- **Phase 3: Validação Local**
  - Rodar `go vet ./...` e `go test ./...` em `backend-go`.
  - Testar `GET http://localhost:8080/healthz` via HTTP client.

## 4. Next Steps
- Executar os ajustes de código e validar os testes do Go.
