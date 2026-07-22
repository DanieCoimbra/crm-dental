# Plano de Implementação: Sistema de Cupons de Desconto e Afiliados

## 1. Visão Geral
Criar um módulo de Cupons de Desconto e Programa de Afiliados/Indicação para os planos de assinatura do SaaS. O sistema permite criar cupons promocionais em porcentagem (ex: 15% OFF) que podem ser aplicados no checkout. Além disso, associa cupons a Afiliados/Parceiros, calculando automaticamente comissões recorrentes sobre as mensalidades pagas pelas clínicas indicadas.

---

## 2. Tecnologias Aplicáveis & Skills Utilizadas
- **Backend:** Go 1.26 + Fiber v2, PostgreSQL (GORM) com transações financeiras ACID, GORM Hooks para disparo de comissões, Worker assíncrono para relatórios.
- **Frontend:** Flutter + Riverpod, componentes de validação instantânea de cupons na UI, painel de afiliados responsivo.
- **Regras de Negócio & Segurança:** Prevenção de auto-indicação, limite de usos por cupom, histórico de comissões e auditoria de repasses.

---

## 3. Regras de Negócio
1. **Estrutura dos Cupons:**
   - **Código Único:** Ex: `ODONTO10`, `BLACKFRIDAY20`.
   - **Tipo de Desconto:** Porcentagem (%) sobre a assinatura (ex: 10%, 20%).
   - **Validade e Limite:** Data de expiração opcional e limite máximo de utilizações (ex: primeiros 50 assinantes).
   - **Recorrência do Desconto:** Aplicável somente no 1º mês ou em todas as renovações.
2. **Programa de Afiliados (Comissão por Indicação):**
   - Cada Afiliado/Parceiro possui um cupom exclusivo amarrado à sua conta.
   - **Porcentagem de Comissão:** Definitiva por contrato (ex: 10% do valor líquido pago pela clínica indicada).
   - **Geração da Comissão:** Toda vez que um webhook de pagamento confirmado (`paid`) for recebido para uma clínica que usou o cupom do afiliado, o sistema registra um crédito em `AffiliateCommission`.
3. **Solicitação e Pagamento de Saques:**
   - O afiliado acompanha seu saldo acumulado no painel (`Saldo Disponível`, `Saldo Pendente`).
   - Saque mínimo configurável (ex: R$ 100,00) via Chave PIX cadastrada.
4. **Prevenção contra Fraudes:**
   - Impede que um usuário com o mesmo CPF/CNPJ ou e-mail de clínica utilize seu próprio cupom de afiliado.

---

## 4. Integração Total (Backend + Frontend)

### 4.1. Backend (Go)

#### Data Models (`internal/core/domain/coupon.go`)
```go
type Coupon struct {
    ID                uint       `gorm:"primaryKey" json:"id"`
    Code              string     `gorm:"uniqueIndex;not null" json:"code"` // "DESCONTO20"
    DiscountPercent   float64    `gorm:"not null" json:"discount_percent"` // 20.0
    AffiliateID       *uint      `gorm:"index" json:"affiliate_id"`        // Nulo se for cupom direto do SaaS
    CommissionPercent float64    `gorm:"default:0" json:"commission_percent"` // 10.0 (% que o afiliado ganha)
    MaxUses           int        `gorm:"default:0" json:"max_uses"`         // 0 = ilimitado
    CurrentUses       int        `gorm:"default:0" json:"current_uses"`
    ExpiresAt         *time.Time `json:"expires_at"`
    Active            bool       `gorm:"default:true" json:"active"`
    CreatedAt         time.Time  `json:"created_at"`
}

type Affiliate struct {
    ID          uint      `gorm:"primaryKey" json:"id"`
    Name        string    `json:"name"`
    Email       string    `gorm:"uniqueIndex" json:"email"`
    PixKey      string    `json:"pix_key"`
    PixKeyType  string    `json:"pix_key_type"` // "cpf", "email", "random"
    TotalEarned int64     `gorm:"default:0" json:"total_earned_cents"`
    Balance     int64     `gorm:"default:0" json:"balance_cents"`
    CreatedAt   time.Time `json:"created_at"`
}

type AffiliateCommission struct {
    ID            uint      `gorm:"primaryKey" json:"id"`
    AffiliateID   uint      `gorm:"index;not null" json:"affiliate_id"`
    ClinicID      uint      `gorm:"index;not null" json:"clinic_id"`
    TransactionID uint      `gorm:"index;not null" json:"transaction_id"`
    AmountCents   int64     `json:"amount_cents"`
    Status        string    `gorm:"default:'pending'" json:"status"` // "pending", "available", "paid"
    CreatedAt     time.Time `json:"created_at"`
}
```

#### API Endpoints (`internal/adapters/http/coupon_handler.go`)
- `POST /api/v1/coupons/validate`: Valida se o cupom existe, está ativo, não expirou e calcula o valor final com desconto.
- `GET /api/v1/affiliates/dashboard`: Retorna saldo, cupons e histórico de indicações do afiliado logado.
- `POST /api/v1/affiliates/withdraw`: Dispara solicitação de saque de comissões via PIX.

---

### 4.2. Frontend (Flutter)

#### Componente de Cupom no Checkout (`lib/features/billing/presentation/widgets/coupon_input_field.dart`)
- Campo de entrada com botão "Aplicar".
- Validação em tempo real exibindo feedback visual:
  - **Sucesso:** Card verde mostrando `Cupom ODONTO10 aplicado! Desconto de 10% (-R$ 19,90)`.
  - **Erro:** Feedback vermelho `Cupom inválido ou expirado`.

#### Painel de Afiliado (`lib/features/affiliates/presentation/affiliate_dashboard_screen.dart`)
- **Cards de Métricas:** Saldo Disponível (R$), Total Já Sacado, Clínicas Indicadas Ativas.
- **Link/Código de Indicação:** Campo para copiar com 1 clique o link de referência ou código do cupom.
- **Tabela de Extrato de Comissões:** Lista de cada mensalidade paga pelas indicações e valor gerado de comissão.

---

## 5. Plano de Testes & Validação
1. **Teste de Unidade (Cálculo de Desconto e Comissão):** Garantir que a matemática de centavos e limites percentuais esteja correta sem arredondamentos imperfeitos.
2. **Teste de Concorrência de Uso Máximo:** Simular requisições concorrentes ao limite de uso do cupom usando `sync.WaitGroup` no Go.
3. **Teste de Autenticação & Autorização:** Garantir que afiliados só possam visualizar seus próprios dados de extrato e saldo.
