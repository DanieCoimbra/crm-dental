# Especificação Detalhada: Fase 3 — Comercial e Financeiro

**Projeto:** SaaS de Gestão — Clínica Dental (Dentista Solo)  
**Documento Relacionado:** [workflow.md](file:///C:/IgnisPath/Crm-Clinica/docs/workflow.md)  
**Data:** 2026-09-04  

---

## 1. Visão Geral da Fase 3

A **Fase 3 (Comercial e Financeiro)** transforma o diagnóstico do Odontograma em vendas reais e gestão financeira simplificada para o consultório solo:
1. Cadastro de **Procedimentos Odontológicos com Preço-Base** (ex.: Restauração, Canal, Limpeza, Extração).
2. Geração de **Orçamentos (Budgets)** vinculados ao paciente e aos dentes/faces marcados no odontograma.
3. Ciclo de vida do Orçamento (`draft` → `sent` → `approved` / `rejected`).
4. **Geração Automática de Contas a Receber & Parcelamento**: Ao aprovar o orçamento, são geradas automaticamente parcelas na tabela `clinic_transactions` / `clinic_installments`.
5. **Fluxo de Caixa Simples & Registro de Pagamentos por Parcela** (dinheiro, cartão, PIX).

---

## 2. Modelagem de Banco de Dados (PostgreSQL / GORM)

### 2.1 Tabela `procedures` (Preço-Base de Procedimentos)
- `id` (UUID / uint, PK)
- `clinic_id` (UUID / uint, FK -> `clinics.id`)
- `name` (VARCHAR(150), NOT NULL) — Ex: Restauração Resina
- `description` (TEXT)
- `base_price_cents` (BIGINT, NOT NULL) — Preço padrão em centavos (R$)
- `duration_minutes` (INT, DEFAULT 30)
- `color` (VARCHAR(20), DEFAULT '#2563EB')
- `created_at`, `updated_at`, `deleted_at`

### 2.2 Tabela `budgets` (Orçamentos)
- `id` (UUID / uint, PK)
- `clinic_id` (UUID / uint, FK -> `clinics.id`)
- `patient_id` (UUID / uint, FK -> `patients.id`)
- `dentist_id` (UUID / uint, FK -> `users.id`)
- `total_amount_cents` (BIGINT, NOT NULL)
- `discount_cents` (BIGINT, DEFAULT 0)
- `final_amount_cents` (BIGINT, NOT NULL)
- `status` (VARCHAR(20), NOT NULL) — `draft`, `sent`, `approved`, `rejected`
- `notes` (TEXT)
- `created_at`, `updated_at`

### 2.3 Tabela `budget_items` (Itens do Orçamento)
- `id` (UUID / uint, PK)
- `budget_id` (UUID / uint, FK -> `budgets.id`)
- `procedure_id` (UUID / uint, FK -> `procedures.id`)
- `tooth_number` (INT, OPTIONAL) — FDI (ex: 11, 26, null para limpeza geral)
- `face` (VARCHAR(20), OPTIONAL) — Ex: MESIAL, OCLUSAL
- `price_cents` (BIGINT, NOT NULL)
- `quantity` (INT, DEFAULT 1)

---

## 3. Endpoints Backend (Go / Fiber)

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/api/v1/procedures` | Lista catálogo de procedimentos com preço-base |
| `POST` | `/api/v1/procedures` | Cadastra novo procedimento |
| `PUT` | `/api/v1/procedures/:id` | Edita procedimento |
| `DELETE` | `/api/v1/procedures/:id` | Exclui procedimento (soft delete) |
| `GET` | `/api/v1/budgets` | Lista orçamentos (filtro por `patient_id` e `status`) |
| `POST` | `/api/v1/budgets` | Cria orçamento a partir do odontograma/procedimentos |
| `GET` | `/api/v1/budgets/:id` | Retorna detalhes do orçamento com itens |
| `POST` | `/api/v1/budgets/:id/approve` | Aprova orçamento e gera automaticamente parcelas em `clinic_transactions` |
| `POST` | `/api/v1/budgets/:id/reject` | Rejeita orçamento |
| `GET` | `/api/v1/financial/transactions` | Lista lançamentos de contas a receber/pagar e parcelas |
| `POST` | `/api/v1/financial/installments/:id/pay` | Registra pagamento de uma parcela (PIX, Dinheiro, Cartão) |
| `GET` | `/api/v1/financial/cash-flow` | Retorna resumo do fluxo de caixa (total recebido, pendente, inadimplência) |

---

## 4. Frontend & Telas (Flutter / Riverpod)

1. **Catálogo de Procedimentos (`features/settings/presentation/procedures_screen.dart`)**:
   - Tabela/lista de procedimentos com cadastro de preço-base.
2. **Aba / Tela de Orçamentos (`features/financial/presentation/budgets_screen.dart`)**:
   - Criação de orçamento selecionando dentes/faces do paciente ou procedimentos gerais.
   - Aplicação de desconto e resumo do valor total.
   - Ação de **"Aprovar Orçamento"** com definição de número de parcelas (ex: 1x a 12x) e forma de pagamento.
3. **Dashboard Financeiro & Contas a Receber (`features/financial/presentation/financial_dashboard_screen.dart`)**:
   - Cards de KPIs (Recebido no Mês, A Receber, Inadimplência).
   - Tabela de parcelas com botão de baixa de pagamento ("Registrar Pagamento").
