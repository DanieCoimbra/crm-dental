# 📄 Contrato de Interfaces & APIs — Feature F03: Appointments & Schedule (SQLC)

**Feature:** F03-sqlc-appointments-schedule  
**Projeto:** Dental Clinic CRM (`backend-go`)  
**Autor:** Go Architect Agent  
**Versão:** 1.0  

---

## 1. Contrato da Interface de Repositório (`AppointmentRepoPort`)

```go
package ports

import (
	"context"
	"time"
	"dental-crm-api/internal/core/domain"
)

type AppointmentRepositoryPort interface {
	ListByRange(ctx context.Context, clinicID uint, start, end time.Time) ([]*domain.Appointment, error)
	Create(ctx context.Context, appt *domain.Appointment) (*domain.Appointment, error)
	UpdateStatus(ctx context.Context, clinicID, apptID uint, status string) error
}
```

---

## 2. Contratos de APIs HTTP Mantidos

| Método | Endpoint | Acesso | Payload / Param | Status |
| :--- | :--- | :--- | :--- | :---: |
| `GET` | `/api/v1/appointments` | Authenticated | `?start=...&end=...` | `200 OK` |
| `POST` | `/api/v1/appointments` | Authenticated | `{"doctor_id": 1, "patient_id": 2, "start_time": "..."}` | `201 Created` |
| `PATCH` | `/api/v1/appointments/:id/status` | Authenticated | `{"status": "completed"}` | `200 OK` |
