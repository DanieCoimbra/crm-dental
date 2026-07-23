# Contract: F12 - Stripe Payments

### 1. Contract Summary
- **Feature**: F12 - Stripe Payments (Cartão e PIX)
- **Version**: 1.0.0
- **Primary Consumer(s)**: Frontend Flutter (SaasCheckoutScreen)

### 2. Inputs (What this feature requires)
- **Endpoint/Method**: `POST /api/v1/saas/create-payment-intent`
- **Payload Schema**:
  ```json
  {
    "plan": "string (enum: basic, pro, premium)",
    "payment_method": "string (enum: card, pix)",
    "promo_code": "string (optional, maxLength 50)"
  }
  ```

### 3. Outputs (What this feature returns)
- **Success Response (200 OK)**: 
  ```json
  {
    "client_secret": "string (pi_..._secret_...)",
    "payment_intent_id": "string (pi_...)",
    "pix_qr_code": "string (optional, payload EMVCo para PIX Copia e Cola)",
    "pix_qr_code_url": "string (optional, URL da imagem SVG do QR Code)",
    "expires_at": "string (ISO 8601, data de expiração do PIX)"
  }
  ```
- **Expected Errors**:
  - `400 Bad Request`: `{"error": "Plano inválido ou método não suportado"}`
  - `402 Payment Required`: `{"error": "Cupom expirado ou limite atingido"}` (Se promo_code fornecido)
  - `500 Internal Server Error`: `{"error": "Erro ao comunicar com o Gateway Stripe"}`

### 4. Business Rules & Limits
- **Rate Limits**: Máximo de 5 requisições de geração de PaymentIntent por hora por clínica, para evitar geração de PIXs massiva e spam na Stripe.
- **Validation Rules**: O plano selecionado dita o valor cravado no código (Backend Source of Truth). O frontend nunca envia o preço em dinheiro no input.
- **State Prerequisites**: O usuário precisa estar autenticado, mesmo que a assinatura atual seja trial vencido (o Middleware libera essa rota de SaaS).

### 5. Events Emitted (Side Effects)
- **Event Name**: `stripe.payment_intent.succeeded` (Webhook Stripe -> Backend Go)
- **Trigger**: Emitted by Stripe when the card is charged or the PIX is paid.
- **Event Payload**: Stripe Webhook JSON Payload padrão. Ação do Backend será ativar a `Subscription` e debitar a comissão de `promo_code` do afiliado (se houver).

### 6. Acceptance Criteria (Integration)
- [ ] Consumer enviando `plan: "invalid"` -> Retorna 400.
- [ ] Requesting PIX method gera `pix_qr_code` com a string válida.
- [ ] Backend Go expõe publicamente `STRIPE_PUBLISHABLE_KEY` ou Flutter o tem em `String.fromEnvironment`.
- [ ] O Frontend consegue interceptar sucesso via `flutter_stripe` ou webhook poll.
