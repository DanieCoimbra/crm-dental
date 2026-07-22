package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
)

type WaitlistRepository struct{}

func NewWaitlistRepository() *WaitlistRepository {
	return &WaitlistRepository{}
}

func (r *WaitlistRepository) Create(waitlist *domain.Waitlist) error {
	return database.DB.Create(waitlist).Error
}

func (r *WaitlistRepository) Update(waitlist *domain.Waitlist) error {
	return database.DB.Save(waitlist).Error
}

func (r *WaitlistRepository) Delete(id uint, clinicID uint) error {
	return database.DB.Where("id = ? AND clinic_id = ?", id, clinicID).Delete(&domain.Waitlist{}).Error
}

func (r *WaitlistRepository) FindPotentialMatches(clinicID, doctorID uint) ([]domain.Waitlist, error) {
	var entries []domain.Waitlist
	err := database.DB.Preload("Patient").Preload("Doctor").Preload("AppointmentType").
		Where("clinic_id = ? AND status = ?", clinicID, "pending").
		Where("doctor_id = ? OR doctor_id IS NULL", doctorID).
		Find(&entries).Error
	return entries, err
}

func (r *WaitlistRepository) FindByID(id uint, clinicID uint) (*domain.Waitlist, error) {
	var waitlist domain.Waitlist
	err := database.DB.Preload("Patient").Where("id = ? AND clinic_id = ?", id, clinicID).First(&waitlist).Error
	if err != nil {
		return nil, err
	}
	return &waitlist, nil
}

func (r *WaitlistRepository) ListByClinic(clinicID uint) ([]domain.Waitlist, error) {
	var waitlists []domain.Waitlist
	err := database.DB.Preload("Patient").Preload("Doctor").Preload("AppointmentType").Where("clinic_id = ?", clinicID).Order("created_at desc").Find(&waitlists).Error
	return waitlists, err
}
