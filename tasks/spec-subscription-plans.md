# Technical Specification: Sistema de Seleção, Contratação e Gerenciamento de Planos

## 1. Technical Overview
- **Feature Name**: Sistema de Seleção, Contratação e Gerenciamento de Planos (SaaS Subscriptions)
- **Tech Stack Used**: 
  - **Backend**: Go (Fiber v2, GORM, Stripe Go SDK `github.com/stripe/stripe-go/v76`)
  - **Frontend**: Flutter (Riverpod, Dio, `flutter_secure_storage`, GoRouter)
- **Architecture Approach**: 
  - Interface pública `/plans` para seleção de planos (Pré-cadastro) e integração via Stripe Checkout Sessions.
  - Validação estrita no backend (`/auth/register-clinic`) ao receber um `session_id` proveniente do checkout pré-pago.
  - Gerenciamento pós-cadastro dentro de **Configurações > Assinatura** via modal incorporado do Stripe / API de cupons.
  - Atualização reativa de status e suporte a período de carência (*Grace Period* 3 dias) controlado por `saas_middleware.go`.

---

## 2. Data Models & Schema

### Database Changes (`backend-go`)
1. **Tabela `used_checkout_sessions`**:
   Armazena as sessões de checkout do Stripe que já foram consumidas para criação de clínicas, evitando o reúso fraudulento de URLs de checkout.

```sql
CREATE TABLE IF NOT EXISTS public.used_checkout_sessions (
    id SERIAL PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL UNIQUE,
    clinic_id INT REFERENCES public.clinics(id),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
```

2. **GORM Structs (`internal/core/domain/subscription.go`)**:

```go
type SubscriptionStatus string

const (
	StatusTrialing   SubscriptionStatus = "trialing"
	StatusActive     SubscriptionStatus = "active"
	StatusPastDue    SubscriptionStatus = "past_due"
	StatusGrace      SubscriptionStatus = "grace_period"
	StatusCanceled   SubscriptionStatus = "canceled"
)

type UsedCheckoutSession struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	SessionID string    `gorm:"size:255;not null;unique" json:"session_id"`
	ClinicID  uint      `gorm:"not null" json:"clinic_id"`
	CreatedAt time.Time `json:"created_at"`
}
```

3. **State Management (`frontend_flutter`)**:
   - `PlansNotifier`: Gerencia o plano selecionado (Mensal vs Anual, Tier) na página pública `/plans`.
   - `SubscriptionNotifier`: Gerencia o plano ativo da clínica logada, data de renovação, status de grace period e disparo do checkout modal nas configurações.

---

## 3. Component Architecture

### Backend (`backend-go`)
- `internal/adapters/handlers/saas_billing_handler.go`:
  - `CreateCheckoutSession`: Gera a sessão do Stripe Checkout com `price_id` e redirecionamento para `/register-clinic?session_id={CHECKOUT_SESSION_ID}`.
  - `ValidateCheckoutSession`: Endpoint interno/público para validar a sessão antes de autorizar o formulário de cadastro.
- `internal/adapters/handlers/webhook_handler.go`:
  - Processa os eventos `checkout.session.completed`, `customer.subscription.updated` e `invoice.payment_failed`.
- `internal/middleware/saas_middleware.go`:
  - Intercepta todas as requisições privadas da API para verificar `subscription.Status`. Se estiver expirada > 3 dias (Grace Period), bloqueia com HTTP 402 Payment Required.

### Frontend (`frontend_flutter`)
- `lib/features/saas/presentation/plans_screen.dart`:
  - Exibe os cartões de planos (Start, Pro, Enterprise) + Card de Trial. Toggle Mensal/Anual.
- `lib/features/auth/presentation/register_screen.dart`:
  - Lê parâmetros da query (`session_id` ou `plan=trial`). Exibe badge informativo de plano pré-selecionado/pago.
- `lib/features/settings/presentation/billing_settings_tab.dart`:
  - Exibe o plano atual, botão de upgrade e campo para aplicar cupons de desconto.

---

## 4. Core Logic & Algorithms

### Fluxo 1: Pré-Cadastro Pago (Stripe Checkout -> Registro)
```
1. Cliente acessa /plans e clica em "Assinar Plano Pro Anual".
2. Frontend chama POST /api/v1/saas/create-checkout-session { plan_id, cycle }.
3. Backend invoca Stripe API e retorna URL da sessão (checkout.stripe.com/c/pay/cs_...).
4. Cliente conclui o pagamento no Stripe.
5. Stripe redireciona para: /register-clinic?session_id=cs_live_12345.
6. Frontend chama GET /api/v1/saas/validate-session?session_id=cs_live_12345.
7. Backend confirma se session.payment_status == "paid" e se não consta em used_checkout_sessions.
8. Formulário de cadastro exibe "Plano Pro Anual vinculado com sucesso!".
9. Ao submeter o cadastro:
   - Backend cria a Clínica e o Owner em transação DB.
   - Associa o Stripe Customer e a Subscription ativa.
   - Insere cs_live_12345 na tabela used_checkout_sessions.
```

### Fluxo 2: Período de Carência (Grace Period de 3 Dias)
```
1. Pagamento de renovação falha (evento invoice.payment_failed) ou Trial de 14 dias expira.
2. Webhook atualiza status para StatusPastDue e define GracePeriodEndsAt = Agora + 3 Dias.
3. Durante os 3 dias:
   - saas_middleware permite acesso às rotas normais.
   - Resposta do profile inclui flag "in_grace_period": true.
   - Topbar no Flutter exibe o Banner de Aviso Amarelo.
4. Após os 3 dias:
   - saas_middleware bloqueia todas as rotas privadas com 402 Payment Required.
   - Frontend redireciona para a tela de regularização de assinatura.
```

---

## 5. Error Handling & Edge Cases

- **Sessão do Stripe Inválida ou Reutilizada**:
  - `GET /validate-session` retorna HTTP 400 `{"error": "Sessão de pagamento inválida ou já utilizada"}`.
- **CNPJ/E-mail Duplicado no Trial**:
  - `POST /register-clinic` verifica existência de CNPJ ou E-mail. Retorna HTTP 409 `{"error": "CNPJ ou E-mail já cadastrado"}`.
- **Falha no Webhook do Stripe**:
  - O sistema implementa uma tarefa de conciliação / polling de status de assinatura ao fazer login caso o status local seja `past_due`.

---

## 6. Security & Performance

- **Security Checks**:
  - **Idempotência no Checkout**: Validação estrita na tabela `used_checkout_sessions`.
  - **Assinatura do Webhook**: Validação de chave secreta do webhook Stripe (`stripe-signature`).
  - **Sanitização**: Sanitização de e-mails (`strings.ToLower(strings.TrimSpace(email))`).
- **Performance Targets**:
  - Validação de sessão do Stripe < 300ms.
  - Resposta do `saas_middleware` < 5ms (leitura indexada no banco).
