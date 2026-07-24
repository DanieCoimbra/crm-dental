# Action Plan: F06-SaaS Billing

## 1. Local Scope
- **Derivation**: Extracted from [PRD Fullstack MVP](../../prd-dental-crm-fullstack.md).
- **Responsibility**: Controlar a vida financeira entre a Clínica e o Sistema (SaaS). Gerenciar período de Trial (14 dias), bloqueio forçado por inadimplência, pagamentos de assinaturas mensais/anuais via Stripe e Mercado Pago (PIX), e cálculo de métricas para os donos da plataforma (MRR, Churn).

## 2. External Dependencies (Before starting)
- Requer `F01-multi-tenant-core` para interceptar as requisições e acionar o bloqueio 402 se não houver assinatura ativa.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] 
  - Criar Webhooks de Stripe e MP com validação criptográfica (HMAC).
  - Esquema de `subscriptions` e planos (`monthly`, `annual`).
- **Phase 2**: [Local Spec & Logic]
  - `SaaSSubscriptionMiddleware` que força HTTP 402 em endpoints protegidos.
  - `TenantGuardOverlay` no Flutter bloqueando a tela e forçando renovação.
  - Tela de Checkout com QR Code PIX e Cartão de Crédito.
- **Phase 3**: [Integration]
  - Testar webhooks disparando localmente via CLI do Stripe para simular pagamentos reais e debloqueio automático em tela.

## 4. Next Steps
- Run the `/spec-write` and `/contract` skills specifically for this folder to architect the webhook handlers and Stripe payment intent flow.
