# 📄 Contrato de Interfaces & APIs — Feature F02: Patients & EMR (SQLC)

**Feature:** F02-sqlc-patients-emr  
**Projeto:** Dental Clinic CRM (`backend-go`)  
**Autor:** Go Architect Agent  
**Versão:** 1.0  

---

## 1. Contrato da Interface de Repositório (`PatientRepoPort`)

```go
package ports

import (
	"context"
	"dental-crm-api/internal/core/domain"
)

type PatientRepositoryPort interface {
	FindByID(ctx context.Context, clinicID, patientID uint) (*domain.Patient, error)
	ListByClinic(ctx context.Context, clinicID uint, page, limit int) ([]*domain.Patient, error)
	Create(ctx context.Context, patient *domain.Patient) (*domain.Patient, error)
	Update(ctx context.Context, patient *domain.Patient) error
	Delete(ctx context.Context, clinicID, patientID, deletedBy uint) error
}
```

---

## 2. Contratos de APIs HTTP Mantidos

| Método | Endpoint | Acesso | Payload / Param | Status |
| :--- | :--- | :--- | :--- | :---: |
| `GET` | `/api/v1/patients` | Authenticated | `?page=1&limit=20` | `200 OK` |
| `POST` | `/api/v1/patients` | Authenticated | `{"name": "...", "cpf": "...", "phone": "..."}` | `201 Created` |
| `GET` | `/api/v1/patients/:id` | Authenticated | Param `:id` | `200 OK` |
| `GET` | `/api/v1/patients/:id/evolutions` | Authenticated | Param `:id` | `200 OK` |
| `POST` | `/api/v1/patients/:id/evolutions` | Authenticated | `{"description": "...", "quill_delta": "..."}` | `201 Created` |
