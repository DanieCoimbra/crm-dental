# Feature Technical Specification: F05-Inventory Control

## 1. Technical Overview
- **Feature**: F05-Inventory Control
- **Tech Stack Used**: Go (Fiber, GORM Event Hooks), Flutter (Riverpod Badges).
- **Architecture Approach**: Event-Driven para automação. Listener interno no Go aguarda encerramento de consultas para movimentar estoque.

## 2. Data Models & Schema
- **Database Changes**:
  - `products`: `id`, `clinic_id`, `name`, `current_stock (DECIMAL)`, `minimum_stock`.
  - `procedure_materials`: `id`, `procedure_id`, `product_id`, `quantity (DECIMAL)`.
  - `stock_movements`: Log imutável (append-only) contendo motivo e variação (+/-).

## 3. Component Architecture (UI)
- `InventoryAlertBadge`: ConsumerWidget do Flutter que fica vermelho e exibe counter numérico (ex: "3") se a lista de itens abaixo do `minimum_stock` for > 0.

## 4. Core Logic & Algorithms
- **Operation: Dedução Assíncrona de Estoque**
  - Step 1: Módulo assina evento `Appointment.Completed`.
  - Step 2: Go inicia Transação. Busca lista de `product_id` mapeados aos procedimentos da consulta.
  - Step 3: Executa update com `SET current_stock = current_stock - ?`.
  - Step 4: Adiciona registros rastreáveis na `stock_movements`.
  - Step 5: Se algum `current_stock` ficar < 0, a transação **não** falha (para não impactar o médico finalizando a consulta). O sistema apenas loga para ajuste humano posterior.

## 5. Error Handling & Edge Cases
- **Scenario**: Transação de dedução falha por Deadlock (duas consultas finalizando ao exato mesmo décimo de segundo descontando o mesmo insumo).
  - **Handling**: Configurar o Worker Interno do Go para realizar *Retry* silencioso 3x.

## 6. Security & Performance
- **Performance Targets**: Updates em lote (`UPDATE ... CASE WHEN ...`) para evitar chamadas de banco (N+1) ao deduzir 20 produtos diferentes de uma vez.
