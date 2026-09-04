# 📐 Especificação Técnica — Feature F04: Financial & Budgets (SQLC)

**Feature:** F04-sqlc-financial-budgets  
**Projeto:** Dental Clinic CRM (`backend-go`)  
**Autor:** Go Architect Agent  
**Versão:** 1.0  

---

## 1. Escopo Técnico & Tabelas SQL

- `clinic_transactions`
- `procedures`
- `budgets`
- `budget_items`

---

## 2. Consultas SQL (SQLC) (`db/queries/financial.sql`)

```sql
-- name: ListTransactionsByClinic :many
SELECT id, clinic_id, patient_id, budget_id, type, category, description, amount, payment_method, due_date, payment_date, status, created_at
FROM clinic_transactions
WHERE clinic_id = $1 AND deleted_at IS NULL
ORDER BY due_date DESC;

-- name: CreateTransaction :one
INSERT INTO clinic_transactions (clinic_id, patient_id, budget_id, type, category, description, amount, payment_method, due_date, status)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
RETURNING id, clinic_id, type, category, amount, status, created_at;

-- name: ListProceduresByClinic :many
SELECT id, clinic_id, code, name, category, base_cost, created_at
FROM procedures
WHERE clinic_id = $1 AND deleted_at IS NULL
ORDER BY name ASC;
```
