# Integration Contract: F07-settings-billing-management

## 1. API Endpoints

### POST `/api/v1/saas/apply-coupon`
- **Auth Required**: True (`RequireRole("OWNER")`)
- **Request Payload**:
```json
{
  "coupon_code": "PROMO20"
}
```
- **Response (200 OK)**:
```json
{
  "message": "Cupom aplicado com sucesso",
  "discount_percent": 20
}
```
- **Response (400 Bad Request)**:
```json
{
  "error": "Cupom inválido ou expirado"
}
```

### POST `/api/v1/saas/change-plan`
- **Auth Required**: True (`RequireRole("OWNER")`)
- **Request Payload**:
```json
{
  "new_plan": "pro | start | enterprise",
  "billing_cycle": "monthly | annual"
}
```
- **Response (200 OK)**:
```json
{
  "message": "Plano alterado com sucesso",
  "status": "active",
  "plan": "pro"
}
```
