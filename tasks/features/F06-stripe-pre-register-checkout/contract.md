# Integration Contract: F06-stripe-pre-register-checkout

## 1. API Endpoints

### POST `/api/v1/saas/create-checkout-session`
- **Auth Required**: False (Public endpoint)
- **Request Payload**:
```json
{
  "plan_tier": "pro | start | enterprise",
  "billing_cycle": "monthly | annual",
  "coupon_code": "string (optional)"
}
```
- **Response (200 OK)**:
```json
{
  "checkout_url": "https://checkout.stripe.com/c/pay/cs_test_...",
  "session_id": "cs_test_..."
}
```

### GET `/api/v1/saas/validate-session`
- **Auth Required**: False
- **Query Parameter**: `session_id=cs_test_...`
- **Response (200 OK)**:
```json
{
  "valid": true,
  "plan_tier": "pro",
  "email": "buyer@example.com"
}
```
- **Response (400 Bad Request)**:
```json
{
  "error": "Sessão de pagamento inválida ou já utilizada"
}
```
