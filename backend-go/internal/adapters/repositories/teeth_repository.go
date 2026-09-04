package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"errors"
	"gorm.io/gorm"
)

type TeethRepository struct{}

func NewTeethRepository() *TeethRepository {
	return &TeethRepository{}
}

func (r *TeethRepository) ListByPatient(clinicID, patientID uint) ([]domain.TeethStatus, error) {
	var statuses []domain.TeethStatus
	err := database.DB.Where("clinic_id = ? AND patient_id = ?", clinicID, patientID).
		Order("tooth_number asc, face asc").
		Find(&statuses).Error
	return statuses, err
}

func (r *TeethRepository) GetByToothNumber(clinicID, patientID uint, toothNumber int) ([]domain.TeethStatus, error) {
	var statuses []domain.TeethStatus
	err := database.DB.Where("clinic_id = ? AND patient_id = ? AND tooth_number = ?", clinicID, patientID, toothNumber).
		Order("face asc").
		Find(&statuses).Error
	return statuses, err
}

func (r *TeethRepository) GetByToothAndFace(clinicID, patientID uint, toothNumber int, face domain.ToothFace) (*domain.TeethStatus, error) {
	var status domain.TeethStatus
	err := database.DB.Where("clinic_id = ? AND patient_id = ? AND tooth_number = ? AND face = ?", clinicID, patientID, toothNumber, face).
		First(&status).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &status, nil
}

func (r *TeethRepository) UpsertStatus(status *domain.TeethStatus) error {
	var existing domain.TeethStatus
	err := database.DB.Where("clinic_id = ? AND patient_id = ? AND tooth_number = ? AND face = ?",
		status.ClinicID, status.PatientID, status.ToothNumber, status.Face).First(&existing).Error

	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return database.DB.Create(status).Error
		}
		return err
	}

	existing.Condition = status.Condition
	existing.UpdatedBy = status.UpdatedBy
	err = database.DB.Save(&existing).Error
	if err == nil {
		status.ID = existing.ID
		status.UpdatedAt = existing.UpdatedAt
	}
	return err
}

func (r *TeethRepository) CreateHistory(history *domain.TeethHistory) error {
	return database.DB.Create(history).Error
}

func (r *TeethRepository) ListHistoryByPatient(clinicID, patientID uint) ([]domain.TeethHistory, error) {
	var history []domain.TeethHistory
	err := database.DB.Where("clinic_id = ? AND patient_id = ?", clinicID, patientID).
		Order("created_at desc").
		Find(&history).Error
	return history, err
}
