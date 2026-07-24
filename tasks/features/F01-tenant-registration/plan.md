# Action Plan: Tenant Registration

## 1. Local Scope
- **Derivation**: Extracted from [prd-auth-multitenancy.md](../../prd-auth-multitenancy.md) e [spec-auth-multitenancy.md](../../spec-auth-multitenancy.md).
- **Responsibility**: Implement the registration flow for new Clinics (Owners). It includes the initial database setup (clinics, users tables, ENUM user_role), the backend signup endpoint in Go, and the Flutter `RegisterScreen`.

## 2. External Dependencies (Before starting)
- Supabase Project credentials setup in `.env`.
- Base Fiber architecture in `backend-go` and Base Flutter architecture in `frontend_flutter`.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] Define DB migrations (SQL) for `clinics` and `users`. Create `Contract` for the API payload.
- **Phase 2**: [Local Spec & Logic] Implement Go handler, Core usecase, and DB repository for inserting Clinic + Owner in a single transaction.
- **Phase 3**: [Integration] Build Flutter `RegisterScreen` with inline validation, Dio integration, and "Enter" key submit.

## 4. Next Steps
- Run `/plan` on this folder or request the agent to start Phase 1.
