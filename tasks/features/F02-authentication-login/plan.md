# Action Plan: Authentication & Login

## 1. Local Scope
- **Derivation**: Extracted from [prd-auth-multitenancy.md](../../prd-auth-multitenancy.md).
- **Responsibility**: Implement the login flow, rate limiting (5 attempts / 15 min lock), JWT token generation, and the Flutter `LoginScreen` with Riverpod state management.

## 2. External Dependencies (Before starting)
- Requires `F01-tenant-registration` database tables to exist.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] Define JWT payload structure (claims) and Login request/response API contracts.
- **Phase 2**: [Local Spec & Logic] Implement Go login usecase with `bcrypt` verification, rate limiting logic updating DB, and JWT generation.
- **Phase 3**: [Integration] Implement Flutter `LoginScreen`, `AuthNotifier` in Riverpod, and secure storage for JWT. Handle `429 Too Many Requests` toast.

## 4. Next Steps
- Run `/plan` on this folder or request the agent to start Phase 1.
