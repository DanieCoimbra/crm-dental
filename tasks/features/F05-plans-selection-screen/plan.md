# Action Plan: F05-plans-selection-screen

## 1. Local Scope
- **Derivation**: Extracted from `tasks/prd-subscription-plans.md` (US-001) and `tasks/spec-subscription-plans.md`.
- **Responsibility**: Implement the public `/plans` screen in Flutter with a pricing matrix (Start, Pro, Enterprise tiers + 14-day Trial card), a Mensal/Anual billing toggle, and navigation logic.

## 2. External Dependencies
- Requires GoRouter setup for route `/plans`.
- Redirection to `/register-clinic?plan=trial` for Trial mode.
- Redirection to Stripe Checkout for Paid mode (F06).

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] Define the route `/plans` in GoRouter and create `plan_model.dart`.
- **Phase 2**: [Local Spec & Logic] Build `plans_screen.dart` with Riverpod `plansProvider` handling toggle state (Mensal/Anual).
- **Phase 3**: [Integration] Connect "Cadastre-se" button on `login_screen.dart` to navigate to `/plans`.

## 4. Next Steps
- Execute implementation of F05 and verify UI in browser.
