# Feature Technical Specification: F06-SaaS Billing

## 1. Technical Overview
- **Feature**: F06-SaaS Billing (Sistema de Pagamentos B2B)
- **Tech Stack Used**: Go (Stripe-go SDK), Fiber (Webhooks & Middlewares), MercadoPago SDK.
- **Architecture Approach**: Middleware Global Invasivo (Força bloqueio 402). Webhooks validados criptograficamente.

## 2. Data Models & Schema
- **Database Changes**:
  - `subscriptions`: `id`, `clinic_id`, `status` (active, past_due, trialing), `current_period_end (TIMESTAMPTZ)`, `trial_ends_at`.
- **State Management**:
  - `TenantGuardOverlay`: Estado de UI no Flutter que engloba toda a app e verifica `status` da Subscription a cada rota navegada.

## 3. Component Architecture (UI)
- `CheckoutDashboard`: Tela isolada que não usa o Layout padrão bloqueado. Permite escolha de Plano (Mensal/Anual), gerando QR Code dinâmico (PIX) ou carregando Elementos Seguros do Stripe.

## 4. Core Logic & Algorithms
- **Operation: Bloqueio Ativo (SaaSSubscriptionMiddleware)**
  - Step 1: Em rotas mutáveis (POST, PUT, DELETE).
  - Step 2: Se `user.clinic.subscription.status != 'active'` E `trial_ends_at < NOW()`.
  - Step 3: Retorna `402 Payment Required`.

- **Operation: Processamento Stripe Webhook**
  - Step 1: Request atinge `/api/v1/webhooks/stripe`.
  - Step 2: Valida Header `Stripe-Signature` usando `endpointSecret`.
  - Step 3: Converte JSON. Se `type == "invoice.payment_succeeded"`, prolonga `current_period_end` em +1 Mês.

## 5. Error Handling & Edge Cases
- **Scenario**: Falha no pagamento PIX e usuário cancela qr-code.
  - **Handling**: A `subscription` fica em `past_due`, aguardando nova intenção. Não excluir os dados da clínica, apenas restringir.

## 6. Security & Performance
- **Security Check**: Somente usuários com `role = 'admin'` da clínica podem ver a rota de Checkout de Assinatura e Dados de Cartão.
