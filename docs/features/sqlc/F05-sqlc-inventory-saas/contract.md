# 📄 Contrato de Interfaces & APIs — Feature F05: Inventory, SaaS & Audit (SQLC)

**Feature:** F05-sqlc-inventory-saas  
**Projeto:** Dental Clinic CRM (`backend-go`)  
**Autor:** Go Architect Agent  
**Versão:** 1.0  

---

## 1. Contrato da Interface de Repositório (`AuditRepoPort`)

```go
package ports

import (
	"context"
	"dental-crm-api/internal/core/domain"
)

type AuditRepositoryPort interface {
	CreateLog(ctx context.Context, log *domain.AuditLog) error
}
```
