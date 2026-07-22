package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
)

type ClinicRepository struct{}

func NewClinicRepository() *ClinicRepository {
	return &ClinicRepository{}
}

func (r *ClinicRepository) Create(clinic *domain.Clinic) error {
	return database.DB.Create(clinic).Error
}

func (r *ClinicRepository) FindByCNPJ(cnpj string) (*domain.Clinic, error) {
	var clinic domain.Clinic
	err := database.DB.Where("cnpj = ?", cnpj).First(&clinic).Error
	if err != nil {
		return nil, err
	}
	return &clinic, nil
}

func (r *ClinicRepository) FindByID(id uint) (*domain.Clinic, error) {
	var clinic domain.Clinic
	err := database.DB.Where("id = ?", id).First(&clinic).Error
	if err != nil {
		return nil, err
	}
	return &clinic, nil
}

func (r *ClinicRepository) Update(clinic *domain.Clinic) error {
	return database.DB.Save(clinic).Error
}
