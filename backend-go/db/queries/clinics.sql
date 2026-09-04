-- name: GetClinicByID :one
SELECT id, name, cnpj, email, phone, status, plan, trial_ends_at, created_at, updated_at
FROM clinics
WHERE id = $1 AND deleted_at IS NULL;

-- name: CreateClinic :one
INSERT INTO clinics (name, cnpj, email, phone, status, plan, trial_ends_at)
VALUES ($1, $2, $3, $4, $5, $6, $7)
RETURNING id, name, cnpj, email, phone, status, plan, trial_ends_at, created_at;

-- name: UpdateClinicStatus :exec
UPDATE clinics
SET status = $1, updated_at = NOW()
WHERE id = $2;
