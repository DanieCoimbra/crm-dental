# Technical Specification: F06-stripe-pre-register-checkout

## 1. Technical Overview
- **Feature Name**: F06 - Stripe Pre-Register Checkout
- **Tech Stack**: Go (Fiber v2, GORM, Stripe SDK `github.com/stripe/stripe-go/v76`), Flutter (Dio).
- **Architecture Approach**: Pre-registration Checkout flow creating a Stripe Checkout Session, verifying session validity upon redirect, and enforcing single-use session idempotency.

## 2. Data Models & Schema
```sql
CREATE TABLE IF NOT EXISTS public.used_checkout_sessions (
    id SERIAL PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL UNIQUE,
    clinic_id INT REFERENCES public.clinics(id),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
```

## 3. Component Architecture
- `SaaSBillingHandler.CreateCheckoutSession`: `POST /api/v1/saas/create-checkout-session`.
- `SaaSBillingHandler.ValidateCheckoutSession`: `GET /api/v1/saas/validate-session`.
- `AuthHandler.Register`: Updated to accept `session_id` and insert into `used_checkout_sessions` in a single DB transaction.

## 4. Core Logic & Algorithms
- Backend creates Stripe Session with `success_url: domain + "/register-clinic?session_id={CHECKOUT_SESSION_ID}"`.
- `ValidateCheckoutSession` queries Stripe API `checkout.sessions.Get(sessionID)` and checks `used_checkout_sessions` table.
