# Feature Contract: F06-SaaS Billing

## 1. Contract Summary
- **Feature**: F06-SaaS Billing
- **Version**: 1.0.0
- **Primary Consumer(s)**: Gateways Externos (Stripe / Mercado Pago enviando Webhooks), Middleware Global Interno.

## 2. Inputs
- **Endpoint**: `POST /api/v1/webhooks/stripe`
- **Headers**: `Stripe-Signature`
- **Payload Schema**: (Stripe Event Object Padrão)
  ```json
  {
    "id": "evt_123",
    "type": "invoice.payment_succeeded",
    "data": {
      "object": {
         "customer": "cus_123",
         "subscription": "sub_123"
      }
    }
  }
  ```

## 3. Outputs
- **Success Response (Webhooks)**: HTTP 200 OK imediato para evitar re-tentativas dos gateways.
- **Output do Middleware Bloqueador (Para o Frontend)**:
  - **Status**: HTTP 402 Payment Required
  - **Payload**:
    ```json
    { "error": "SUBSCRIPTION_EXPIRED", "message": "Seu trial/plano expirou." }
    ```

## 4. Business Rules & Limits
- **Security Rules**: Webhooks sem Header criptográfico ou com hash divergente DEVEM ser sumariamente recusados (HTTP 401).

## 5. Events Emitted (Side Effects)
- **Event Name**: `Subscription.Reactivated`
- **Trigger**: Quando um pagamento bem sucedido resgata uma conta expirada (Libera acesso no Redis/Cache do Middleware).

## 6. Acceptance Criteria (Integration)
- [ ] Envio manual via CLI do Stripe Webhook aciona renovação instantânea e libera o HTTP 402 da API.
- [ ] Requisições seguras não-financeiras da clínica inadimplente recebem 402, mas requisições de leitura (GET, se aplicável na regra) podem ser liberadas dependendo da política definida pelo negócio.
