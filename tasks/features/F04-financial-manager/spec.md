# Feature Technical Specification: F04-Financial Manager

## 1. Technical Overview
- **Feature**: F04-Financial Manager
- **Tech Stack Used**: Go (Fiber, GORM), Flutter (`printing` para PDF, `fl_chart`).
- **Architecture Approach**: Transacional. Utilização estrita de `int64` (centavos) para precisão monetária contra falhas de ponto flutuante.

## 2. Data Models & Schema
- **Database Changes**:
  - `clinic_transactions`: `id`, `clinic_id`, `total_amount_cents (BIGINT)`, `type (VARCHAR)`, `status`.
  - `clinic_installments`: `id`, `transaction_id`, `clinic_id`, `amount_cents`, `due_date`, `status`, `installment_number`.

## 3. Component Architecture (UI)
- `TransactionListScreen`: Tabela paginada com botões "Dar Baixa".
- `FinancialDashboard`: Gráficos de barra `fl_chart` separando verde (Receita) e vermelho (Despesa).

## 4. Core Logic & Algorithms
- **Operation: Parcelamento Matemático Seguro**
  - Step 1: Payload recebe Total: 10000 centavos, Parcelas: 3.
  - Step 2: Base = 10000 / 3 = 3333 centavos. Resto = 10000 % 3 = 1 centavo.
  - Step 3: Go gera 3 parcelas. Parcela 1 recebe o resto (3334 centavos). Parcela 2 e 3 recebem 3333. A soma garante exatidão.

## 5. Error Handling & Edge Cases
- **Scenario**: Tentativa de estorno de parcela já estornada.
  - **Handling**: A API deve garantir idempotência (Retornar HTTP 200 avisando que já foi executado sem duplicar operação no DB).

## 6. Security & Performance
- **Performance Targets**: Cálculos analíticos para Dashboard (MRR interno) usarão *Materialized Views* ou cache Redis se o número de transações for maciço.
