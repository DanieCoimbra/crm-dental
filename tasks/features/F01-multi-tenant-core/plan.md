# Action Plan: F01-Multi-tenant Core

## 1. Local Scope
- **Derivation**: Extracted from [PRD Fullstack MVP](../../prd-dental-crm-fullstack.md) and [Tech Spec](../../spec-dental-crm-fullstack.md).
- **Responsibility**: Implementar a fundação de segurança e isolamento B2B do SaaS. Este módulo gerencia o `clinic_id` via tokens JWT, injeta escopos globais no GORM para impedir vazamento de dados entre clínicas e serve como a base de autenticação para todos os outros módulos.

## 2. External Dependencies (Before starting)
- Nenhuma. Este é o módulo de infraestrutura raiz (Core). Todos os outros dependem dele.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] 
  - Definir DTOs de Login e Token.
  - Estabelecer a interface do Middleware (Go).
- **Phase 2**: [Local Spec & Logic]
  - Implementar o `TenantMiddleware` injetando `clinic_id` no Fiber.
  - Criar função utilitária `WithTenant(clinicID)` para o GORM.
  - Configurar `AuthNotifier` no Riverpod (Flutter) com persistência segura (`flutter_secure_storage`).
- **Phase 3**: [Integration]
  - Testar rotas bloqueadas (401 Unauthorized) e validação de tenant cruzado.

## 4. Next Steps
- Run the `/spec-write` and `/contract` skills specifically for this folder to define exact DB scopes and API endpoints.
