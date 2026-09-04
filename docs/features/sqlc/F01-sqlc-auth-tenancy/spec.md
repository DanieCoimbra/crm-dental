# 📐 Especificação Técnica — Feature F01: Auth, Multi-Tenancy & Roles (SQLC)

**Feature:** F01-sqlc-auth-tenancy  
**Projeto:** Dental Clinic CRM (`backend-go`)  
**Autor:** Go Architect Agent  
**Versão:** 1.0  

---

## 1. Escopo Técnico & Tabelas SQL

Esta feature abrange o gerenciamento de autenticação, multi-tenancy e permissões RBAC através das tabelas:
- `clinics`
- `roles`
- `users`
- `used_checkout_sessions`

---

## 2. Consultas SQL (SQLC) (`db/queries/auth.sql`)

```sql
-- name: GetUserByID :one
SELECT id, clinic_id, role_id, name, email, password, cpf, phone, avatar, theme_preference, failed_attempts, locked_until, created_at, updated_at
FROM users
WHERE id = $1 AND deleted_at IS NULL;

-- name: GetUserByEmail :one
SELECT id, clinic_id, role_id, name, email, password, cpf, phone, avatar, theme_preference, failed_attempts, locked_until, created_at, updated_at
FROM users
WHERE email = $1 AND deleted_at IS NULL;

-- name: CreateUser :one
INSERT INTO users (clinic_id, role_id, name, email, password, cpf, phone, avatar)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
RETURNING id, clinic_id, role_id, name, email, password, created_at, updated_at;

-- name: GetClinicByID :one
SELECT id, name, cnpj, email, phone, status, plan, trial_ends_at, created_at, updated_at
FROM clinics
WHERE id = $1 AND deleted_at IS NULL;

-- name: CreateClinic :one
INSERT INTO clinics (name, cnpj, email, phone, status, plan, trial_ends_at)
VALUES ($1, $2, $3, $4, $5, $6, $7)
RETURNING id, name, cnpj, email, phone, status, plan, trial_ends_at, created_at;

-- name: GetRoleByID :one
SELECT id, name, permissions, clinic_id, created_at, updated_at
FROM roles
WHERE id = $1;

-- name: ListRoles :many
SELECT id, name, permissions, clinic_id, created_at, updated_at
FROM roles
WHERE clinic_id IS NULL OR clinic_id = $1;
```

---

## 3. Adaptação do Repositório (`internal/adapters/repositories/user_repository.go`)

O repositório `UserRepository` irá inicializar uma instância das queries compiladas pelo `sqlc`:

```go
package repositories

import (
	"context"
	"dental-crm-api/db/sqlc"
	"dental-crm-api/internal/core/domain"
	"github.com/jackc/pgx/v5/pgxpool"
)

type UserRepository struct {
	pool    *pgxpool.Pool
	queries *sqlc.Queries
}

func NewUserRepository(pool *pgxpool.Pool) *UserRepository {
	return &UserRepository{
		pool:    pool,
		queries: sqlc.New(pool),
	}
}

func (r *UserRepository) FindByEmail(ctx context.Context, email string) (*domain.User, error) {
	row, err := r.queries.GetUserByEmail(ctx, email)
	if err != nil {
		return nil, err
	}

	return &domain.User{
		ID:        uint(row.ID),
		ClinicID:  uint(row.ClinicID),
		Name:      row.Name,
		Email:     row.Email,
		Password:  row.Password,
		CreatedAt: row.CreatedAt,
		UpdatedAt: row.UpdatedAt,
	}, nil
}
```
