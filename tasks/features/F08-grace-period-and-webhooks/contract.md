# Integration Contract: F08-grace-period-and-webhooks

## 1. Webhook Endpoint
### POST `/api/v1/webhooks/stripe`
- **Auth Required**: False (Public endpoint validated via `Stripe-Signature` header).
- **Handled Events**:
  - `checkout.session.completed`: Finalizes subscription linkage.
  - `customer.subscription.updated`: Updates active status/plan.
  - `invoice.payment_failed`: Initiates 3-day Grace Period (`status = "grace_period"`).

## 2. Middleware Control Contract (`saas_middleware.go`)
- **Active / Trial (valid)**: `200 OK` -> `Next()`
- **Grace Period (within 3 days)**: `200 OK` + header `X-Grace-Period: true` -> `Next()`
- **Grace Period Expired (> 3 days)**: `402 Payment Required`
```json
{
  "error": "Assinatura expirada. Por favor regularize seu plano nas configurações."
}
```
