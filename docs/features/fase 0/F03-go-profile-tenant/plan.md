# Plano de Execução do Domínio: Profile & Tenant Status (`F03-go-profile-tenant`)

## 1. Escopo Local
- **Origem:** Extraído de `docs/global/prd.md` e `docs/global/spec.md`.
- **Responsabilidade:** Endpoint de consulta de perfil (`GET /api/v1/profile`), retornando dados do usuário e status da clínica/trial.

## 2. Dependências Externas
- Requer `F01-go-auth-onboarding` e `F02-go-middlewares-rbac`.

## 3. Fases de Execução
- **Fase 1:** Definição do DTO de Perfil e Clínica.
- **Fase 2:** Caso de uso `GetProfile`.
- **Fase 3:** Contrato de resposta do perfil.
