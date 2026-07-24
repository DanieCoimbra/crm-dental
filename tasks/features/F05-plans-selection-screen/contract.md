# Integration Contract: F05-plans-selection-screen

## 1. UI Navigation Contract
- **Trigger**: Click "Cadastre-se" on `/login` -> Navigates to `/plans`.
- **Action Trial**: Click "Modo Trial" on `/plans` -> Navigates to `/register-clinic?plan=trial`.
- **Action Paid**: Click "Assinar [Plano]" on `/plans` -> Triggers F06 Stripe Checkout API call.

## 2. Route Parameters
- `/plans?billing=annual` or `/plans?billing=monthly` (default: monthly).

## 3. UI Aesthetics & Theme Standards
- Glassmorphism dark/light design.
- "Mais Popular" badge on Pro tier card.
