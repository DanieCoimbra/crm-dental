-- name: ListAppointmentsByRange :many
SELECT a.id, a.clinic_id, a.doctor_id, a.patient_id, a.room_id, a.appointment_type_id, a.start_time, a.end_time, a.status, a.notes,
       p.full_name as patient_name, u.name as doctor_name
FROM appointments a
JOIN patients p ON a.patient_id = p.id
JOIN users u ON a.doctor_id = u.id
WHERE a.clinic_id = $1 AND a.start_time >= $2 AND a.end_time <= $3 AND a.deleted_at IS NULL
ORDER BY a.start_time ASC;

-- name: CreateAppointment :one
INSERT INTO appointments (clinic_id, doctor_id, patient_id, room_id, appointment_type_id, start_time, end_time, notes, status)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
RETURNING id, clinic_id, doctor_id, patient_id, start_time, end_time, status, created_at;

-- name: UpdateAppointmentStatus :exec
UPDATE appointments
SET status = $1, updated_at = NOW()
WHERE id = $2 AND clinic_id = $3;
