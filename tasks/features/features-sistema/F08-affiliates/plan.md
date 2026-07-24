# Action Plan: F08-affiliates

## 1. Local Scope
- **Derivation**: Extracted from [PRD Dental CRM Fullstack](../../prd-dental-crm-fullstack.md) (US-007) and [Spec](../../spec-dental-crm-fullstack.md).
- **Responsibility**: Gerenciar afiliados parceiros do SaaS e validar códigos de cupons (promo codes) para clínicas ativarem planos com desconto, injetando comissão recorrente na carteira do parceiro.

## 2. External Dependencies (Before starting)
- Requires `F06-saas-billing` para intercepção dos webhooks do Stripe (`invoice.payment_succeeded`).

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts]
  - Modelar `Affiliate` e `PromoCode`.
  - Definir DTOs para validação de cupom no ato do checkout.
- **Phase 2**: [Local Spec & Logic]
  - Desenvolver CRUD (somente Super Admin) de afiliados e cupons.
  - Implementar a lógica de ativação de comissão: No webhook de sucesso, verificar se a clínica tem promo_code e depositar valor (Transaction).
- **Phase 3**: [Integration]
  - Inserir campo "Possui Cupom?" na tela `SaasCheckoutScreen` (F06) no Flutter.
  - Mostrar feedback de "Cupom Aplicado - X% off".

## 4. Next Steps
- Run `/spec-write` in this folder to detail the database and UI specifics.
- Run `/contract` to establish the HTTP API definitions.
