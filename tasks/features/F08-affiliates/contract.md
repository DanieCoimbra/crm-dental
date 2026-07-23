# Contract: F08-affiliates

## 1. Overview
Define como os códigos promocionais serão ativados pelo frontend (clínica) e como o webhook da Stripe calculará a comissão.

## 2. Inputs (API Requests)
- `POST /api/v1/saas/validate-coupon`
  - Body: `{ "code": "BLACKFRIDAY20" }`

## 3. Outputs (API Responses)
- **200 OK**: `{ "valid": true, "discount_percent": 20.0 }`
- **404 Not Found**: `{ "valid": false, "error": "Cupom inválido ou inativo." }`

## 4. Integration Rules
- A criação da assinatura (Checkout/Session do Stripe) no `F06` agora precisa injetar nos metadados o `promo_code_id` para que a assinatura nasça com a referência.
- O webhook no backend não pode calcular a comissão duas vezes se a requisição for duplicada pelo Stripe (Usar idempotência ou checar log).

## 5. Boundaries
- Não há interface web/portal para o próprio afiliado logar. O SuperAdmin saca os valores por fora.
