package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
)

type MedicalDocumentRepository struct{}

func NewMedicalDocumentRepository() *MedicalDocumentRepository {
	return &MedicalDocumentRepository{}
}

func (r *MedicalDocumentRepository) Create(doc *domain.MedicalDocument) error {
	return database.DB.Create(doc).Error
}

func (r *MedicalDocumentRepository) ListByPatient(clinicID, patientID uint) ([]domain.MedicalDocument, error) {
	var docs []domain.MedicalDocument
	err := database.DB.Where("clinic_id = ? AND patient_id = ?", clinicID, patientID).
		Preload("Dentist").
		Preload("Patient").
		Order("created_at desc").
		Find(&docs).Error
	return docs, err
}

func (r *MedicalDocumentRepository) FindByID(id, clinicID uint) (*domain.MedicalDocument, error) {
	var doc domain.MedicalDocument
	err := database.DB.Where("id = ? AND clinic_id = ?", id, clinicID).
		Preload("Dentist").
		Preload("Patient").
		First(&doc).Error
	if err != nil {
		return nil, err
	}
	return &doc, nil
}

func (r *MedicalDocumentRepository) Delete(id, clinicID uint) error {
	return database.DB.Where("id = ? AND clinic_id = ?", id, clinicID).
		Delete(&domain.MedicalDocument{}).Error
}
