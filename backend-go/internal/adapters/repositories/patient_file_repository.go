package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
)

type PatientFileRepository struct{}

func NewPatientFileRepository() *PatientFileRepository {
	return &PatientFileRepository{}
}

func (r *PatientFileRepository) Create(file *domain.PatientFile) error {
	return database.DB.Create(file).Error
}

func (r *PatientFileRepository) ListByPatient(clinicID uint, patientID uint) ([]domain.PatientFile, error) {
	var files []domain.PatientFile
	err := database.DB.Where("clinic_id = ? AND patient_id = ?", clinicID, patientID).Order("created_at desc").Find(&files).Error
	return files, err
}

func (r *PatientFileRepository) FindByID(id uint, clinicID uint) (*domain.PatientFile, error) {
	var file domain.PatientFile
	err := database.DB.Where("id = ? AND clinic_id = ?", id, clinicID).First(&file).Error
	if err != nil {
		return nil, err
	}
	return &file, nil
}

func (r *PatientFileRepository) Delete(id uint, clinicID uint) error {
	return database.DB.Where("id = ? AND clinic_id = ?", id, clinicID).Delete(&domain.PatientFile{}).Error
}
