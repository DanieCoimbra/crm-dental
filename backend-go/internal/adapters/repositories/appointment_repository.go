package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"time"

	"gorm.io/gorm"
)

type AppointmentRepository struct{}

func NewAppointmentRepository() *AppointmentRepository {
	return &AppointmentRepository{}
}

func (r *AppointmentRepository) Create(appointment *domain.Appointment) error {
	return database.DB.Create(appointment).Error
}

func (r *AppointmentRepository) ListByClinic(clinicID uint, start string, end string) ([]domain.Appointment, error) {
	var appointments []domain.Appointment
	query := database.DB.Preload("Doctor").Preload("Patient").Preload("Room").Preload("AppointmentType").Where("clinic_id = ?", clinicID)

	if start != "" {
		query = query.Where("start_time >= ?", start)
	}
	if end != "" {
		// As appointments usually have a duration, the end filter might be start_time <= end or end_time <= end.
		// Let's assume start_time <= end for simpler period filtering.
		query = query.Where("start_time <= ?", end)
	}

	err := query.Order("start_time asc").Find(&appointments).Error
	return appointments, err
}

func (r *AppointmentRepository) Delete(id uint, clinicID uint, deletedBy uint) error {
	return database.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&domain.Appointment{}).Where("id = ? AND clinic_id = ?", id, clinicID).Update("deleted_by", deletedBy).Error; err != nil {
			return err
		}
		return tx.Where("id = ? AND clinic_id = ?", id, clinicID).Delete(&domain.Appointment{}).Error
	})
}

func (r *AppointmentRepository) FindByID(id uint, clinicID uint) (*domain.Appointment, error) {
	var appointment domain.Appointment
	err := database.DB.Preload("Doctor").Preload("Patient").Preload("Clinic").Preload("Room").Preload("AppointmentType").Where("id = ? AND clinic_id = ?", id, clinicID).First(&appointment).Error
	return &appointment, err
}

func (r *AppointmentRepository) UpdateStatus(id uint, clinicID uint, status string) error {
	return database.DB.Model(&domain.Appointment{}).Where("id = ? AND clinic_id = ?", id, clinicID).Update("status", status).Error
}

func (r *AppointmentRepository) Update(appointment *domain.Appointment) error {
	return database.DB.Save(appointment).Error
}

func (r *AppointmentRepository) HasOverlap(clinicID, doctorID uint, excludeApptID *uint, start, end time.Time) (bool, error) {
	var count int64
	query := database.DB.Model(&domain.Appointment{}).
		Where("clinic_id = ? AND doctor_id = ?", clinicID, doctorID).
		Where("status != ?", "cancelled").
		Where("start_time < ? AND end_time > ?", end, start)

	if excludeApptID != nil {
		query = query.Where("id != ?", *excludeApptID)
	}

	err := query.Count(&count).Error
	return count > 0, err
}

func (r *AppointmentRepository) HasAppointmentInProgress(clinicID, doctorID uint) (bool, error) {
	var count int64
	err := database.DB.Model(&domain.Appointment{}).
		Where("clinic_id = ? AND doctor_id = ? AND status = ?", clinicID, doctorID, "in_progress").
		Count(&count).Error
	return count > 0, err
}
