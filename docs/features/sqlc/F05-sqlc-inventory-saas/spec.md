# 📐 Especificação Técnica — Feature F05: Inventory, SaaS & Audit (SQLC)

**Feature:** F05-sqlc-inventory-saas  
**Projeto:** Dental Clinic CRM (`backend-go`)  
**Autor:** Go Architect Agent  
**Versão:** 1.0  

---

## 1. Escopo Técnico & Tabelas SQL

- `inventory_items`
- `inventory_transactions`
- `procedure_materials`
- `subscriptions`
- `audit_logs`

---

## 2. Consultas SQL (SQLC) (`db/queries/inventory.sql` & `audit.sql`)

```sql
-- name: CreateAuditLog :one
INSERT INTO audit_logs (clinic_id, user_id, action, entity, entity_id, ip_address, user_agent, details)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
RETURNING id, clinic_id, action, entity, created_at;
```
