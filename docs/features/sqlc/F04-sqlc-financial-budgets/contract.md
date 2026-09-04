# 📄 Contrato de Interfaces & APIs — Feature F04: Financial & Budgets (SQLC)

**Feature:** F04-sqlc-financial-budgets  
**Projeto:** Dental Clinic CRM (`backend-go`)  
**Autor:** Go Architect Agent  
**Versão:** 1.0  

---

## 1. Contrato da Interface de Repositório (`FinancialRepoPort`)

```go
package ports

import (
	"context"
	"dental-crm-api/internal/core/domain"
)

type FinancialRepositoryPort interface {
	ListTransactions(ctx context.Context, clinicID uint) ([]*domain.ClinicTransaction, error)
	CreateTransaction(ctx context.Context, tx *domain.ClinicTransaction) (*domain.ClinicTransaction, error)
}
```

---

## 2. Contratos de APIs HTTP Mantidos

| Método | Endpoint | Acesso | Payload / Param | Status |
| :--- | :--- | :--- | :--- | :---: |
| `GET` | `/api/v1/financial/transactions` | Authenticated | Query filters | `200 OK` |
| `POST` | `/api/v1/financial/transactions` | Authenticated | `{"type": "income", "amount": 150.00, ...}` | `201 Created` |
| `GET` | `/api/v1/procedures` | Authenticated | - | `200 OK` |
