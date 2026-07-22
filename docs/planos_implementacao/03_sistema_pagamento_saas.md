# Plano de Implementação: Sistema de Pagamento e Assinaturas (SaaS Multi-tenant)

## 1. Visão Geral
Implementar um sistema completo de gestão de pagamentos, cobrança recorrente e assinaturas para a plataforma SaaS do CRM Clínico. O módulo gerencia o período de degustação (Trial de 14 dias), possibilita a contratação dos planos Mensal e Anual através de gateways de pagamento integrados (Stripe e Mercado Pago), lida com webhooks de renovação/cancelamento e gera métricas de faturamento para os administradores do SaaS (MRR, ARR, Churn).

---

## 2. Tecnologias Aplicáveis & Skills Utilizadas
- **Backend:** Go 1.26 + Fiber v2, PostgreSQL (GORM), SDK Oficial do Stripe para Go (`github.com/stripe/stripe-go/v76`), SDK / API REST Mercado Pago, GORM Transactions, Cron Jobs / Scheduling.
- **Frontend:** Flutter + Riverpod, `flutter_stripe` (ou Stripe Elements Web View / Checkout Redirect), Formatação monetária BRL.
- **Arquitetura & Segurança:** Assinatura HHMAC em Webhooks, idempotência na gravação de transações, validação de Tenant Middleware no Go.

---

## 3. Regras de Negócio
1. **Ciclo de Vida do Trial (14 Dias):**
   - Ao cadastrar uma nova clínica no SaaS, a coluna `trial_ends_at` é preenchida com `NOW() + 14 dias`.
   - Durante os 14 dias, o acesso a todas as funcionalidades é total.
   - Restando 3 dias para o fim do trial, um banner é exibido no topo do sistema.
   - Após o vencimento sem plano ativo, o middleware bloqueia rotas de gravação (POST, PUT, DELETE), permitindo apenas acesso à tela de Faturamento/Assinatura.
2. **Planos de Assinatura:**
   - **Plano Mensal:** Cobrança recorrente a cada 30 dias (Ex: R$ 199,00/mês por clínica).
   - **Plano Anual:** Cobrança única ou parcelada a cada 365 dias com desconto (Ex: R$ 1.990,00/ano = ~16% de desconto).
3. **Métodos de Pagamento:**
   - **Stripe:** Cartão de Crédito e Apple Pay / Google Pay.
   - **Mercado Pago:** PIX com atualização em tempo real (Webhook instantâneo) e Cartão de Crédito local.
4. **Métricas de Faturamento SaaS (Super Admin):**
   - **MRR (Monthly Recurring Revenue):** Soma da receita mensal de clientes ativos.
   - **ARR (Annual Recurring Revenue):** MRR * 12.
   - **Taxa de Conversão de Trial:** % de clínicas que converteram trial em pagantes.
   - **Churn Rate:** Taxa de cancelamento mensal.

---

## 4. Integração Total (Backend + Frontend)

### 4.1. Backend (Go)

#### Data Models (`internal/core/domain/billing.go`)
```go
type Plan struct {
    ID          uint    `gorm:"primaryKey" json:"id"`
    Name        string  `json:"name"`        // "Mensal", "Anual"
    Code        string  `gorm:"uniqueIndex" json:"code"` // "monthly", "annual"
    PriceCents  int64   `json:"price_cents"` // Preço em centavos (ex: 19900)
    Interval    string  `json:"interval"`    // "month", "year"
    StripePriceID string `json:"stripe_price_id"`
    Active      bool    `gorm:"default:true" json:"active"`
}

type Subscription struct {
    ID                   uint      `gorm:"primaryKey" json:"id"`
    ClinicID             uint      `gorm:"uniqueIndex;not null" json:"clinic_id"`
    PlanID               uint      `gorm:"not null" json:"plan_id"`
    Status               string    `gorm:"index;not null" json:"status"` // "trialing", "active", "past_due", "canceled"
    Gateway              string    `json:"gateway"`                      // "stripe", "mercadopago"
    ExternalSubscriptionID string  `gorm:"index" json:"external_subscription_id"`
    CurrentPeriodStart   time.Time `json:"current_period_start"`
    CurrentPeriodEnd     time.Time `json:"current_period_end"`
    TrialEndsAt          *time.Time`json:"trial_ends_at"`
    CanceledAt           *time.Time`json:"canceled_at"`
    CreatedAt            time.Time `json:"created_at"`
    UpdatedAt            time.Time `json:"updated_at"`
}

type Transaction struct {
    ID                   uint      `gorm:"primaryKey" json:"id"`
    ClinicID             uint      `gorm:"index;not null" json:"clinic_id"`
    SubscriptionID       uint      `json:"subscription_id"`
    AmountCents          int64     `json:"amount_cents"`
    Gateway              string    `json:"gateway"`
    ExternalID           string    `gorm:"uniqueIndex" json:"external_id"`
    Status               string    `json:"status"` // "paid", "failed", "refunded"
    PaymentMethod        string    `json:"payment_method"` // "credit_card", "pix"
    PaidAt               *time.Time`json:"paid_at"`
    CreatedAt            time.Time `json:"created_at"`
}
```

#### Middleware de Bloqueio SaaS (`internal/middleware/saas_subscription_middleware.go`)
- Intercepta requisições HTTP e valida se a clínica do usuário logado possui `Subscription.Status == "active"` ou `TrialEndsAt > NOW()`.
- Se bloqueado e a rota não for `/api/v1/billing/*`, retorna HTTP `402 Payment Required`.

#### Handlers de Webhook (`internal/adapters/http/webhook_handler.go`)
- `/api/v1/webhooks/stripe`: Valida assinatura de webhook via Stripe SDK (`webhook.ConstructEvent`). Atualiza status da assinatura e cria transação.
- `/api/v1/webhooks/mercadopago`: Valida token de notificação e busca detalhes do pagamento PIX na API Mercado Pago.

---

### 4.2. Frontend (Flutter)

#### Billing Dashboard (`lib/features/billing/presentation/billing_screen.dart`)
- **Status Banner:** Indica dias de trial restantes ou status do plano atual ("Plano Mensal - Ativo").
- **Plan Selector Component:** Toggle entre Faturamento Mensal e Anual com destaque para economia de 16%.
- **Checkout Modal / PIX Modal:**
  - Se selecionado PIX (Mercado Pago): Exibe QR Code legível com botão "Copiar Código PIX" e contador regressivo. Polling de status a cada 3 segundos via Riverpod.
  - Se selecionado Cartão (Stripe): Formulário seguro de entrada de cartão ou redirecionamento seguro para Checkout hosted.

#### SaaS Admin Panel (`lib/features/admin/presentation/saas_metrics_screen.dart`)
- Cards de KPI: **MRR**, **ARR**, **Total de Assinantes**, **Novas Clínicas no Mês**.
- Tabela de histórico de pagamentos globais e status de inadimplência.

---

## 5. Plano de Testes & Validação
1. **Teste de Lógica do Middleware:** Simular requisições com clínicas com trial vencido e validar retorno HTTP `402`.
2. **Teste de Webhook com Assinatura Falsa:** Enviar payload de webhook sem a chave secreta e garantir rejeição `401 Unauthorized`.
3. **Teste de Transição de Estado:** Simular evento de pagamento confirmado via webhook e verificar ativação instantânea do plano.
