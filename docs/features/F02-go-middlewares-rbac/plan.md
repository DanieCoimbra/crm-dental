# Plano de Execução do Domínio: Middlewares & Role Security (`F02-go-middlewares-rbac`)

## 1. Escopo Local
- **Origem:** Extraído de `docs/global/prd.md` e `docs/global/spec.md`.
- **Responsabilidade:** Desenvolvimento dos middlewares de Fiber `AuthMiddleware`, `TenantMiddleware` e `RoleGuard` para extração de claims (`clinic_id`, `role`) e proteção de endpoints.

## 2. Dependências Externas
- Requer os modelos de Auth definidos em `F01-go-auth-onboarding`.

## 3. Fases de Execução
- **Fase 1:** Definição da assinatura dos Middlewares e erros HTTP de bloqueio (401/403).
- **Fase 2:** Especificação da injeção de contexto `c.Locals()`.
- **Fase 3:** Contrato de erros padronizados.
