# Action Plan: F08-grace-period-and-webhooks

## 1. Local Scope
- **Derivation**: Extracted from `tasks/prd-subscription-plans.md` (US-005) and `tasks/spec-subscription-plans.md`.
- **Responsibility**: Implement the 3-day Grace Period logic, yellow warning topbar banner, `saas_middleware.go` enforcement, and Stripe Webhooks listener.

## 2. External Dependencies
- Requires `saas_middleware.go`.
- Requires Stripe Webhook signing secret (`STRIPE_WEBHOOK_SECRET`).

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] Configure webhook route `POST /api/v1/webhooks/stripe`.
- **Phase 2**: [Local Spec & Logic] Implement 3-day Grace Period check in `saas_middleware.go`.
- **Phase 3**: [Integration] Add `GracePeriodBanner` widget to topbar in Flutter.

## 4. Next Steps
- Execute implementation of F08 and run QA contract audit.
