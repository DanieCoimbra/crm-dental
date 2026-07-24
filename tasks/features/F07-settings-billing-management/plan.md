# Action Plan: F07-settings-billing-management

## 1. Local Scope
- **Derivation**: Extracted from `tasks/prd-subscription-plans.md` (US-004) and `tasks/spec-subscription-plans.md`.
- **Responsibility**: Implement the embedded plan management tab in **Settings > Assinatura**, plan change modal, and coupon application logic.

## 2. External Dependencies
- Requires `RequireRole("OWNER")` middleware.
- Requires `subscriptionProvider` in Flutter.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] Define `POST /api/v1/saas/apply-coupon` and `POST /api/v1/saas/change-plan`.
- **Phase 2**: [Local Spec & Logic] Build `billing_settings_tab.dart` and `change_plan_dialog.dart` in Flutter.
- **Phase 3**: [Integration] Test coupon validation and plan upgrade modal.

## 4. Next Steps
- Execute implementation of F07 and run QA contract audit.
