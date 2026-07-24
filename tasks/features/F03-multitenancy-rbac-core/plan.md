# Action Plan: Multitenancy & RBAC Core

## 1. Local Scope
- **Derivation**: Extracted from [prd-auth-multitenancy.md](../../prd-auth-multitenancy.md).
- **Responsibility**: Ensure strict data isolation (Multitenancy) and Role-Based Access Control. Implement Go middleware to extract `clinic_id`, enforce DB query filters, and Flutter Dio interceptors for global 401/403 handling.

## 2. External Dependencies (Before starting)
- Requires `F02-authentication-login` for JWT generation and Flutter state management.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] Define Fiber middleware interface and GORM scoping rules.
- **Phase 2**: [Local Spec & Logic] Build `RequireAuth` Go middleware. Build GORM scope `WithTenant(clinicId)`.
- **Phase 3**: [Integration] Add `AuthDioInterceptor` in Flutter. Create conditional UI wrappers in Flutter based on `Role`.

## 4. Next Steps
- Run `/plan` on this folder or request the agent to start Phase 1.
