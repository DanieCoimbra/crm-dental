# 📄 Contrato de Interfaces & APIs — Feature F01: Auth, Multi-Tenancy & Roles (SQLC)

**Feature:** F01-sqlc-auth-tenancy  
**Projeto:** Dental Clinic CRM (`backend-go`)  
**Autor:** Go Architect Agent  
**Versão:** 1.0  

---

## 1. Contrato da Interface de Repositório (`UserRepoPort`)

```go
package ports

import (
	"context"
	"dental-crm-api/internal/core/domain"
)

type UserRepositoryPort interface {
	FindByID(ctx context.Context, id uint) (*domain.User, error)
	FindByEmail(ctx context.Context, email string) (*domain.User, error)
	Create(ctx context.Context, user *domain.User) (*domain.User, error)
	Update(ctx context.Context, user *domain.User) error
	UpdatePassword(ctx context.Context, userID uint, hashedPassword string) error
}

type ClinicRepositoryPort interface {
	FindByID(ctx context.Context, id uint) (*domain.Clinic, error)
	Create(ctx context.Context, clinic *domain.Clinic) (*domain.Clinic, error)
}
```

---

## 2. Contratos de APIs HTTP Mantidos (Inalterados para o Frontend)

| Método | Endpoint | Request Body | Response Payload | Status |
| :--- | :--- | :--- | :--- | :---: |
| `POST` | `/api/v1/auth/login` | `{"email": "...", "password": "..."}` | `{"token": "...", "user": {...}}` | `200 OK` |
| `POST` | `/api/v1/auth/register-clinic` | `{"clinic_name": "...", "user_name": "...", "email": "...", "password": "..."}` | `{"token": "...", "user": {...}, "clinic": {...}}` | `201 Created` |
| `GET` | `/api/v1/profile` | Header `Authorization: Bearer <token>` | `{"id": 1, "name": "...", "email": "...", "clinic": {...}}` | `200 OK` |
