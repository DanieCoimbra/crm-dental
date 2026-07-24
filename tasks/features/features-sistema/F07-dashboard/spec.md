# Technical Spec: F07-dashboard

## 1. Technical Overview
- **Feature**: Dashboard Gerencial
- **Tech Stack Used**: Go (Fiber), GORM (PostgreSQL), Flutter (Riverpod), fl_chart.
- **Architecture Approach**: API REST com consultas de agregação SQL pesadas. No front, gráficos estatísticos e Riverpod para gerenciar estado assíncrono.

## 2. Data Models & Schema
- **Database Changes**: Nenhuma tabela nova. Utilizará tabelas existentes (`clinic_transactions`, `appointments`) filtradas pelo `clinic_id`.
- **State Management**:
  - `DashboardNotifier`: StateNotifier que guarda um objeto de classe `DashboardStats` com métricas consolidadas.

## 3. Component Architecture
- `DashboardScreen`: 
  - **Responsibility**: Renderiza layout grid com Cards Superiores (receita mensal, taxa de faltas) e `fl_chart` para linha do tempo.
- `StatCard`:
  - **Props**: `title`, `value`, `icon`, `trend`.
  - **Responsibility**: Exibe um KPI único.

## 4. Core Logic & Algorithms
- **Operation**: Extração de Estatísticas (Backend)
  - Step 1: Pegar `clinic_id` do JWT.
  - Step 2: GORM executa `SELECT sum(amount_cents) FROM clinic_transactions WHERE type='income' AND extract(month from due_date) = current_month`.
  - Step 3: Calcula taxa de cancelamento: `canceled_appointments / total_appointments`.

## 5. Error Handling & Edge Cases
- **Scenario**: Clínica nova sem dados (sem consultas ou transações).
  - **Handling**: Backend retorna 0, Frontend exibe estado vazio limpo nos gráficos.

## 6. Security & Performance
- **Security Check**: Somente usuários com `role == 'admin'` ou `role == 'owner'` têm permissão para acessar o endpoint.
- **Performance Targets**: A query SQL será otimizada com índices B-Tree compostos; a resposta deve ocorrer em < 50ms.
