package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"

	"gorm.io/gorm"
)

type ClinicalEvolutionRepository struct{}

func NewClinicalEvolutionRepository() *ClinicalEvolutionRepository {
	return &ClinicalEvolutionRepository{}
}

func (r *ClinicalEvolutionRepository) Create(evolution *domain.ClinicalEvolution) error {
	return database.DB.Create(evolution).Error
}

func (r *ClinicalEvolutionRepository) ListByPatient(clinicID uint, patientID uint, limit, offset int) ([]domain.ClinicalEvolution, int64, error) {
	var evolutions []domain.ClinicalEvolution
	var total int64

	query := database.DB.Model(&domain.ClinicalEvolution{}).Where("clinic_id = ? AND patient_id = ?", clinicID, patientID)
	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := query.Preload("User", func(db *gorm.DB) *gorm.DB {
		return db.Select("id, name, medical_registry, avatar, role_id")
	}).Order("created_at desc").Limit(limit).Offset(offset).Find(&evolutions).Error

	return evolutions, total, err
}

func (r *ClinicalEvolutionRepository) Delete(id uint, clinicID uint) error {
	return database.DB.Where("id = ? AND clinic_id = ?", id, clinicID).Delete(&domain.ClinicalEvolution{}).Error
}
