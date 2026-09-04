-- name: GetPatientByID :one
SELECT id, clinic_id, full_name, cpf_encrypted, email, phone_encrypted, cep, street, neighborhood, number, health_insurance, birth_date, medical_history, notes_encrypted, weight, created_at, updated_at
FROM patients
WHERE id = $1 AND clinic_id = $2 AND deleted_at IS NULL;

-- name: ListPatientsByClinic :many
SELECT id, clinic_id, full_name, cpf_encrypted, email, phone_encrypted, birth_date, health_insurance, created_at
FROM patients
WHERE clinic_id = $1 AND deleted_at IS NULL
ORDER BY full_name ASC
LIMIT $2 OFFSET $3;

-- name: CreatePatient :one
INSERT INTO patients (clinic_id, full_name, cpf_encrypted, email, phone_encrypted, cep, street, neighborhood, number, health_insurance, birth_date, medical_history, notes_encrypted, weight)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
RETURNING id, clinic_id, full_name, cpf_encrypted, email, phone_encrypted, created_at;

-- name: SoftDeletePatient :exec
UPDATE patients
SET deleted_at = NOW(), deleted_by = $3
WHERE id = $1 AND clinic_id = $2;
