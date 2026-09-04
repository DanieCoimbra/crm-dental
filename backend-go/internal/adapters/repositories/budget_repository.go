package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"

	"gorm.io/gorm"
)

type BudgetRepository struct {
	db *gorm.DB
}

func NewBudgetRepository() *BudgetRepository {
	return &BudgetRepository{db: database.DB}
}

func (r *BudgetRepository) Create(budget *domain.Budget) error {
	return r.db.Create(budget).Error
}

func (r *BudgetRepository) GetByID(id uint, clinicID uint) (*domain.Budget, error) {
	var budget domain.Budget
	err := r.db.Preload("Items").
		Preload("Items.Procedure").
		Preload("Patient").
		Preload("Dentist").
		Where("id = ? AND clinic_id = ?", id, clinicID).
		First(&budget).Error
	if err != nil {
		return nil, err
	}
	return &budget, nil
}

func (r *BudgetRepository) ListByClinic(clinicID uint, patientID *uint, status string) ([]domain.Budget, error) {
	var budgets []domain.Budget
	query := r.db.Preload("Items").
		Preload("Patient").
		Preload("Dentist").
		Where("clinic_id = ?", clinicID)

	if patientID != nil && *patientID > 0 {
		query = query.Where("patient_id = ?", *patientID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	err := query.Order("created_at desc").Find(&budgets).Error
	return budgets, err
}

func (r *BudgetRepository) Update(budget *domain.Budget) error {
	return r.db.Save(budget).Error
}
