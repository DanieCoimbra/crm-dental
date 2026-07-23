# Technical Spec: F08-affiliates

## 1. Technical Overview
- **Feature**: Sistema de Cupons de Afiliados (SaaS)
- **Tech Stack Used**: Go (Fiber), GORM (PostgreSQL).
- **Architecture Approach**: API restrita ao Master-Admin, integração ao Stripe Webhook para gerar comissões automáticas.

## 2. Data Models & Schema
- **Database Changes**:
  - `affiliates`: `id, name, email, commission_percent (decimal), balance_cents (bigint), created_at`.
  - `promo_codes`: `id, code (varchar, unique), discount_percent (decimal), affiliate_id (FK), active (boolean)`.
  - `subscriptions` (Alteração): Adicionar coluna `promo_code_id (FK opcional)`.
- **State Management**:
  - Não requer UI do Flutter complexa para a clínica, apenas o modal de `ApplyPromoCode`.

## 3. Component Architecture
- `PromoCodeInput` (Na tela SaasCheckout):
  - **Props**: `onApply(String code)`
  - **Responsibility**: Validar e aplicar cupom.

## 4. Core Logic & Algorithms
- **Operation**: Validação de Cupom
  - Step 1: Front envia POST para `/api/v1/saas/validate-coupon`.
  - Step 2: Backend busca cupom ativo.
- **Operation**: Pagamento de Comissão (Webhook)
  - Step 1: Stripe notifica fatura paga (`invoice.payment_succeeded`).
  - Step 2: Se a Subscription tem `promo_code_id`, acha o Afiliado.
  - Step 3: Pega a porcentagem da comissão, multiplica pelo valor pago, e roda `UPDATE affiliates SET balance_cents = balance_cents + X`.

## 5. Error Handling & Edge Cases
- **Scenario**: Cupom não existe ou está inativo.
  - **Handling**: Retornar `404 Not Found` de forma amigável no checkout.

## 6. Security & Performance
- **Security Check**: A rota de criação de cupons e afiliados usa middleware restrito apenas para o e-mail ou role master do SaaS.
