# Plano de Implementação: Módulo Financeiro da Clínica

## 1. Visão Geral
Desenvolver o módulo de Gestão Financeira interna da clínica odontológica. Este módulo permite registrar tratamentos cobrados dos pacientes, pagamentos à vista ou parcelados, emissão e impressão de recibos com validade fiscal/comprovante, controle de contas a pagar e receber, e relatórios analíticos de fluxo de caixa mensal.

---

## 2. Tecnologias Aplicáveis & Skills Utilizadas
- **Backend:** Go 1.26 + Fiber v2, PostgreSQL (GORM) com escopo Multi-Tenant obrigatório por `clinic_id`, GORM Transactions para divisão de parcelas.
- **Frontend:** Flutter + Riverpod, `pdf` / `printing` package para geração nativa de recibos PDF, `fl_chart` para visualização gráfica de receitas x despesas.
- **Engenharia Financeira:** Precisão de valores em centavos (`int64`), manipulação rigorosa de datas de vencimento e quitação (`TIMESTAMPTZ`).

---

## 3. Regras de Negócio
1. **Lançamentos Financeiros (Entradas e Saídas):**
   - **Receitas (Entradas):** Pagamentos de consultas, tratamentos ortodônticos, restaurações, etc.
   - **Despesas (Saídas):** Aluguel da clínica, conta de luz, salários, insumos odontológicos, etc.
2. **Parcelamento de Tratamentos (Installments):**
   - Possibilidade de parcelar um valor total (Ex: Tratamento de R$ 1.200,00 em 6x de R$ 200,00).
   - O sistema gera automaticamente 6 registros de parcelas amarrados à transação principal com datas de vencimento mensais.
   - Baixa individual de parcelas conforme o paciente efetua o pagamento na recepção.
3. **Formas de Pagamento:**
   - Dinheiro, PIX, Cartão de Débito, Cartão de Crédito, Boleto Bancário.
4. **Geração e Impressão de Recibos:**
   - Recibo emitido com logotipo da clínica, nome do paciente, CPF, lista de procedimentos, valor pago por extenso e assinatura do profissional responsável.
5. **Relatórios Financeiros:**
   - Fluxo de Caixa Diário/Mensal, DRE Simplificado, Inadimplência (Parcelas Vencidas e Não Pagas).

---

## 4. Integração Total (Backend + Frontend)

### 4.1. Backend (Go)

#### Data Models (`internal/core/domain/financial.go`)
```go
type ClinicTransaction struct {
    ID            uint                `gorm:"primaryKey" json:"id"`
    ClinicID      uint                `gorm:"index;not null" json:"clinic_id"`
    PatientID     *uint               `gorm:"index" json:"patient_id"`
    Type          string              `gorm:"index;not null" json:"type"` // "income" (receita), "expense" (despesa)
    Category      string              `json:"category"`                   // "Procedimento", "Aluguel", "Material"
    Description   string              `json:"description"`
    TotalAmount   int64               `json:"total_amount_cents"`
    PaymentMethod string              `json:"payment_method"`
    Status        string              `json:"status"`                     // "paid", "pending", "overdue", "canceled"
    DueDate       time.Time           `json:"due_date"`
    PaidAt        *time.Time          `json:"paid_at"`
    Installments  []ClinicInstallment `gorm:"foreignKey:TransactionID" json:"installments,omitempty"`
    CreatedAt     time.Time           `json:"created_at"`
}

type ClinicInstallment struct {
    ID            uint       `gorm:"primaryKey" json:"id"`
    TransactionID uint       `gorm:"index;not null" json:"transaction_id"`
    ClinicID      uint       `gorm:"index;not null" json:"clinic_id"`
    Number        int        `json:"number"`        // Ex: 1, 2, 3...
    TotalNumber   int        `json:"total_number"`  // Ex: 6
    AmountCents   int64      `json:"amount_cents"`
    DueDate       time.Time  `json:"due_date"`
    PaidAt        *time.Time `json:"paid_at"`
    Status        string     `json:"status"`        // "pending", "paid", "overdue"
}
```

#### UseCases (`internal/core/usecase/financial_usecase.go`)
- `CreateTransactionWithInstallments(tx *domain.ClinicTransaction, totalInstallments int) error`:
  - Abre transação no PostgreSQL.
  - Divide `TotalAmount` por `totalInstallments` (ajustando restos de centavos na primeira parcela).
  - Salva a transação e gera as `ClinicInstallment` com `DueDate = Date.AddMonths(i)`.
- `RegisterPayment(installmentID uint) error`: Dá baixa na parcela e atualiza o status geral da transação principal se todas forem quitadas.

---

### 4.2. Frontend (Flutter)

#### Módulo Financeiro (`lib/features/financial/presentation/`)
- **Dashboard Screen (`financial_dashboard_screen.dart`):**
  - Gráfico de barras comparativo (Receitas em Verde x Despesas em Vermelho).
  - KPI Cards: **Faturamento do Mês**, **Contas a Receber**, **Contas Vencidas**.
- **Lista de Lançamentos & Parcelamentos (`transaction_list_screen.dart`):**
  - Tabela com filtros por período, status (Pago/Pendente) e pesquisa por Paciente.
  - Botão de Ação Rápida: "Dar Baixa" em parcelas pendentes.
- **Modal de Emissão de Recibo (`receipt_dialog.dart`):**
  - Visualização prévia do PDF do recibo usando o pacote `printing`.
  - Botões para "Imprimir Direct" ou "Baixar PDF / Enviar WhatsApp".

---

## 5. Plano de Testes & Validação
1. **Teste de Arredondamento de Parcelas:** Testar divisão de R$ 100,00 em 3x (33,34 + 33,33 + 33,33) garantindo que a soma exata das parcelas seja igual ao montante.
2. **Teste de Isolamento Multi-tenant:** Garantir via testes de integração que a Clínica A jamais consiga ver lançamentos ou recibos da Clínica B.
3. **Teste de Impressão PDF:** Validar a formatação do recibo gerado no Flutter em diferentes tamanhos de tela e papéis (A4 e impressora térmica).
