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

-- name: UpdateUserPassword :exec
UPDATE users
SET password = $1, updated_at = NOW()
WHERE id = $2;

-- name: UpdateUserFailedAttempts :exec
UPDATE users
SET failed_attempts = $1, locked_until = $2, updated_at = NOW()
WHERE id = $3;
