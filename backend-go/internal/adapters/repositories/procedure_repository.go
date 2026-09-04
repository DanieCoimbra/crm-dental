package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"

	"gorm.io/gorm"
)

type ProcedureRepository struct {
	db *gorm.DB
}

func NewProcedureRepository() *ProcedureRepository {
	return &ProcedureRepository{db: database.DB}
}

func (r *ProcedureRepository) Create(procedure *domain.Procedure) error {
	return r.db.Create(procedure).Error
}

func (r *ProcedureRepository) GetByID(id uint, clinicID uint) (*domain.Procedure, error) {
	var procedure domain.Procedure
	err := r.db.Where("id = ? AND clinic_id = ?", id, clinicID).First(&procedure).Error
	if err != nil {
		return nil, err
	}
	return &procedure, nil
}

func (r *ProcedureRepository) ListByClinic(clinicID uint) ([]domain.Procedure, error) {
	var procedures []domain.Procedure
	err := r.db.Where("clinic_id = ?", clinicID).Order("name asc").Find(&procedures).Error
	return procedures, err
}

func (r *ProcedureRepository) Update(procedure *domain.Procedure) error {
	return r.db.Save(procedure).Error
}

func (r *ProcedureRepository) Delete(id uint, clinicID uint) error {
	return r.db.Where("id = ? AND clinic_id = ?", id, clinicID).Delete(&domain.Procedure{}).Error
}
