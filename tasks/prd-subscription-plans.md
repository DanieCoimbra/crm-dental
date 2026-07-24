# PRD: Sistema de Seleção, Contratação e Gerenciamento de Planos (SaaS Subscriptions)

## 1. Executive Summary / Introduction

- **Problem Statement**: Atualmente, o cadastro no sistema CRM atribui automaticamente um plano de teste (trial) sem permitir que o usuário conheça ou escolha planos pagos antecipadamente. Além disso, clínicas cadastradas não possuem um fluxo intuitivo para contratação, upgrade de plano ou aplicação de cupons nas configurações.
- **Proposed Solution**: Implementar um fluxo completo de checkout e contratação em dois momentos: (1) **Pré-cadastro**, onde o usuário seleciona entre Trial ou Planos Pagos na nova tela `/plans` antes de registrar a clínica (com validação prévia de pagamento via Stripe Checkout e `session_id`); e (2) **Pós-cadastro**, onde o Owner pode gerenciar, alterar o plano e aplicar cupons em um modal/checkout nativo em Configurações, contando com suporte a período de carência (*Grace Period* de 3 dias) e prevenção de duplicação por CNPJ/E-mail.
- **Goals & Success Metrics**:
  - Aumentar a conversão direta de cadastros para assinaturas pagas em 25%.
  - Garantir 100% de integridade no vínculo entre a sessão de pagamento no Stripe e a criação da conta da clínica.
  - Reduzir o tempo de contratação/upgrade pós-cadastro para menos de 3 cliques.
  - Eliminar tentativas de abuso de Trial duplicado por mesmo CNPJ ou E-mail.

---

## 2. User Stories

### US-001: Seleção de Planos Pré-Cadastro (Tela `/plans`)
**Description:** As a prospective clinic owner, I want to view a pricing matrix (Tiers x Monthly/Annual) before registering so that I can choose between a 14-day trial or immediate paid subscription.

**Acceptance Criteria:**
- [ ] Clicking "Cadastre-se" on `/login` redirects the user to `/plans`.
- [ ] The `/plans` page displays a toggle between **Mensal** and **Anual** billing.
- [ ] The `/plans` page displays tiers (Básico/Start, Pro, Enterprise) and a **Modo Trial (14 dias grátis)** card.
- [ ] Selecting "Modo Trial" redirects to `/register-clinic?plan=trial`.
- [ ] Typecheck/lint passes.
- [ ] **[UI]** Verify responsive layout and interactive toggle on browser.

### US-002: Flow de Compra Pré-Cadastro (Stripe Checkout Integration)
**Description:** As a prospective customer, I want to pay for a subscription before creating my account so that my clinic is initialized directly with the paid plan.

**Acceptance Criteria:**
- [ ] Selecting a paid plan on `/plans` opens the Stripe Checkout session.
- [ ] Optional promo code field is supported on checkout.
- [ ] Upon successful payment, Stripe redirects to `/register-clinic?session_id={CHECKOUT_SESSION_ID}`.
- [ ] The backend validates `session_id` against Stripe API and ensures it hasn't been used yet.
- [ ] Completing the registration form creates the `Clinic` and `User` (Owner) tied to the paid subscription.
- [ ] Typecheck/lint passes.

### US-003: Validação de E-mail e Prevenção de Abuso no Trial
**Description:** As a system administrator, I want to enforce email verification and block duplicate CNPJ/Emails on Trial so that users cannot abuse free trials endlessly.

**Acceptance Criteria:**
- [ ] Registration with an already existing CNPJ or E-mail returns HTTP 409 Conflict.
- [ ] Email verification link or verification code is sent upon trial registration.
- [ ] Attempting to re-register a trial with a previously registered CNPJ/email is blocked with an explicit UI alert.
- [ ] Typecheck/lint passes.

### US-004: Contratação e Upgrade Pós-Cadastro em Configurações
**Description:** As a logged-in Clinic Owner, I want to upgrade or change my plan in Settings via an embedded modal so that I can scale my subscription effortlessly.

**Acceptance Criteria:**
- [ ] Owner accesses **Settings > Assinatura / Planos**.
- [ ] UI displays current plan status, renewal date, and active tier.
- [ ] Clicking "Alterar Plano" opens an embedded Stripe modal/checkout.
- [ ] Coupon codes can be entered and applied before checkout confirmation.
- [ ] Webhook `customer.subscription.updated` updates `Subscription` status in backend DB in real time.
- [ ] Typecheck/lint passes.
- [ ] **[UI]** Verify plan modal and active plan badge in browser.

### US-005: Gestão de Inadimplência e Grace Period (3 Dias)
**Description:** As a system owner, I want a 3-day grace period for failed payments or trial expiration so that clinics have time to pay before access is restricted.

**Acceptance Criteria:**
- [ ] When payment fails or trial expires, status changes to `grace_period` for 3 days.
- [ ] A yellow warning banner is displayed across the topbar: *"Seu pagamento/trial venceu. Você tem 3 dias para regularizar."*
- [ ] After 3 days, `saas_middleware` blocks access to protected routes and redirects to `/settings/billing` or `/plans`.
- [ ] Typecheck/lint passes.

---

## 3. Functional Requirements

- **FR-1**: The system must provide a public `/plans` route displaying plan tiers (Start, Pro, Enterprise) and billing cycles (Monthly, Annual).
- **FR-2**: Selecting a paid plan prior to registration must create a Stripe Checkout Session and redirect the client to Stripe.
- **FR-3**: After a successful checkout, the client must be redirected to `/register-clinic?session_id=CS_...`.
- **FR-4**: The backend `/auth/register-clinic` endpoint must accept an optional `session_id`, validate its authenticity with Stripe, and verify it has not been claimed by another clinic.
- **FR-5**: The system must reject Trial registration if the CNPJ or E-mail already exists in the system (HTTP 409).
- **FR-6**: The system must trigger an e-mail verification flow for new Trial accounts.
- **FR-7**: In **Settings > Assinatura**, the Clinic Owner must be able to view their current plan, renewal date, and trigger an embedded upgrade/downgrade modal.
- **FR-8**: Coupons must be supported both during pre-registration Stripe Checkout and post-registration upgrade modal.
- **FR-9**: The backend `saas_middleware` must enforce a 3-day Grace Period after trial expiry or payment failure before blocking access to clinic features.

---

## 4. Non-Goals (Out of Scope)

- **Custom Enterprise Invoicing**: Manual bank transfer invoicing for custom enterprise contracts is out of scope for v1.
- **Multi-Currency**: Only BRL (R$) currency is supported in v1.
- **Crypto / PIX Manual Reconciliation**: Only automated Stripe (Credit Card & PIX via Stripe) processing is in scope.

---

## 5. Technical Specifications & Design Considerations

### Architecture Overview
```
[User] ---> [/login] --("Cadastre-se")--> [/plans]
                                              |
                     +------------------------+------------------------+
                     | (Mode: Trial)                                   | (Mode: Paid)
                     v                                                 v
           [/register-clinic?plan=trial]                     [Stripe Checkout Page]
                     |                                                 |
                     v                                                 v (Payment OK)
           [Email Verification]                              [/register-clinic?session_id=...]
                     |                                                 |
                     +------------------------+------------------------+
                                              |
                                              v
                              [POST /api/v1/auth/register-clinic]
                                              |
                                              v
                               [Clinic + Subscription + Owner User]
```

### Integration Points
- **Stripe Checkout API**: Session creation (`stripe.checkout.sessions.create`) with `client_reference_id` or metadata.
- **Stripe Webhooks**: `checkout.session.completed`, `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.payment_failed`.
- **Backend Fiber Middleware**: `saas_middleware.go` checking `Subscription` status and `GracePeriod`.
- **Flutter Riverpod**: `subscriptionProvider` managing subscription state and plan upgrades.

### UI/UX Requirements
- **Design Aesthetic**: Premium dark/light themes, sleek glassmorphism cards for plan tiers, clear badges for "Mais Popular" and "14 Dias Grátis".
- **Feedback**: Toast notifications for coupon validation, inline errors for invalid `session_id`, warning topbar during Grace Period.

### Security & Privacy
- **Idempotency**: `session_id` must be stored or marked as consumed in DB (`used_checkout_sessions`) so it cannot be re-used to register multiple clinics.
- **Validation**: Strict server-side verification of Stripe events via Webhook signature.

---

## 6. Risks & Open Questions

- **Risk**: User closes the browser after payment on Stripe before reaching `/register-clinic?session_id=...`.
  - *Mitigation*: Stripe Webhook `checkout.session.completed` sends a transactional email to the customer's email containing a single-use registration link with the `session_id`.
- **Risk**: Timeouts during Stripe Session validation.
  - *Mitigation*: Cache session validation state or use fast REST calls to Stripe API with 5s timeout.

---

## 7. Phased Rollout Plan

- **Phase 1 (MVP)**: Public `/plans` selection screen, pre-registration Trial flow, post-registration Settings billing tab.
- **Phase 2**: Pre-registration Stripe Checkout integration & `session_id` validation.
- **Phase 3**: Grace Period (3 days) topbar banner & automated Webhook processing for renewals/failures.
