package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
)

type ClinicalNoteRepository struct{}

func NewClinicalNoteRepository() *ClinicalNoteRepository {
	return &ClinicalNoteRepository{}
}

func (r *ClinicalNoteRepository) Create(note *domain.ClinicalNote) error {
	return database.DB.Create(note).Error
}

func (r *ClinicalNoteRepository) ListByPatient(clinicID, patientID uint) ([]domain.ClinicalNote, error) {
	var notes []domain.ClinicalNote
	err := database.DB.Where("clinic_id = ? AND patient_id = ?", clinicID, patientID).
		Preload("Dentist").
		Order("attendance_date desc, created_at desc").
		Find(&notes).Error
	return notes, err
}

func (r *ClinicalNoteRepository) FindByID(id, clinicID uint) (*domain.ClinicalNote, error) {
	var note domain.ClinicalNote
	err := database.DB.Where("id = ? AND clinic_id = ?", id, clinicID).
		Preload("Dentist").
		First(&note).Error
	if err != nil {
		return nil, err
	}
	return &note, nil
}
