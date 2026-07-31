# Plano de Execução do Domínio: Auth & Tenant Onboarding (`F01-go-auth-onboarding`)

## 1. Escopo Local
- **Origem:** Extraído de `docs/global/prd.md` e `docs/global/spec.md`.
- **Responsabilidade:** Implementação das rotas de cadastro atômico da clínica com trial de 14 dias (`POST /api/v1/auth/register-clinic`), login (`POST /api/v1/auth/login`) e geração/validação de tokens JWT Supabase.

## 2. Dependências Externas
- Nenhuma. Este é o domínio base de autenticação do backend.

## 3. Fases de Execução
- **Fase 1:** Definição dos Contratos de DTOs e Endpoints HTTP.
- **Fase 2:** Especificação técnica dos casos de uso de Autenticação e Registro.
- **Fase 3:** Implementação dos handlers Fiber e repositórios GORM.
