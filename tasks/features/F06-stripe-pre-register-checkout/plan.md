# Action Plan: F06-stripe-pre-register-checkout

## 1. Local Scope
- **Derivation**: Extracted from `tasks/prd-subscription-plans.md` (US-002) and `tasks/spec-subscription-plans.md`.
- **Responsibility**: Implement the pre-registration Stripe Checkout integration, session creation endpoint, session validation endpoint, and registration linking with `session_id`.

## 2. External Dependencies
- Requires Stripe API key in `.env`.
- Requires `used_checkout_sessions` DB table.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] Create `used_checkout_sessions` GORM model and table migration.
- **Phase 2**: [Local Spec & Logic] Implement `POST /api/v1/saas/create-checkout-session` and `GET /api/v1/saas/validate-session`.
- **Phase 3**: [Integration] Update `auth_handler.go` (`RegisterClinicOwner`) to accept `session_id` and mark it as consumed in DB.

## 4. Next Steps
- Execute implementation of F06 and run QA contract audit.
