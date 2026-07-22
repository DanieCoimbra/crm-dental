package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
)

type ClinicalEvolutionRepository struct{}

func NewClinicalEvolutionRepository() *ClinicalEvolutionRepository {
	return &ClinicalEvolutionRepository{}
}

func (r *ClinicalEvolutionRepository) Create(evolution *domain.ClinicalEvolution) error {
	return database.DB.Create(evolution).Error
}

func (r *ClinicalEvolutionRepository) ListByPatient(clinicID uint, patientID uint) ([]domain.ClinicalEvolution, error) {
	var evolutions []domain.ClinicalEvolution
	err := database.DB.Preload("User").Where("clinic_id = ? AND patient_id = ?", clinicID, patientID).Order("created_at desc").Find(&evolutions).Error
	return evolutions, err
}

func (r *ClinicalEvolutionRepository) Delete(id uint, clinicID uint) error {
	return database.DB.Where("id = ? AND clinic_id = ?", id, clinicID).Delete(&domain.ClinicalEvolution{}).Error
}
