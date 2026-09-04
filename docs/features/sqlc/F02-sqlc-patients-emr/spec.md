# 📐 Especificação Técnica — Feature F02: Patients & EMR (SQLC)

**Feature:** F02-sqlc-patients-emr  
**Projeto:** Dental Clinic CRM (`backend-go`)  
**Autor:** Go Architect Agent  
**Versão:** 1.0  

---

## 1. Escopo Técnico & Tabelas SQL

Esta feature abrange o prontuário eletrônico do paciente:
- `patients`
- `clinical_evolutions`
- `clinical_notes`
- `medical_documents`
- `teeth_statuses`

---

## 2. Consultas SQL (SQLC) (`db/queries/patients.sql`)

```sql
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

-- name: ListEvolutionsByPatient :many
SELECT id, clinic_id, patient_id, doctor_id, appointment_id, description, quill_delta, created_at
FROM clinical_evolutions
WHERE patient_id = $1 AND clinic_id = $2 AND deleted_at IS NULL
ORDER BY created_at DESC;

-- name: CreateClinicalEvolution :one
INSERT INTO clinical_evolutions (clinic_id, patient_id, doctor_id, appointment_id, description, quill_delta)
VALUES ($1, $2, $3, $4, $5, $6)
RETURNING id, clinic_id, patient_id, doctor_id, description, created_at;
```
