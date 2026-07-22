package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
)

type RoleRepository struct{}

func NewRoleRepository() *RoleRepository {
	return &RoleRepository{}
}

func (r *RoleRepository) FindByName(name string) (*domain.Role, error) {
	var role domain.Role
	// Buscar primeiro a global, se não achar cria
	err := database.DB.Where("name = ?", name).First(&role).Error
	if err != nil {
		// Se não existe, cria a role global
		role = domain.Role{Name: name}
		database.DB.Create(&role)
	}
	return &role, nil
}

func (r *RoleRepository) FindByID(id uint) (*domain.Role, error) {
	var role domain.Role
	err := database.DB.First(&role, id).Error
	return &role, err
}

func (r *RoleRepository) List(clinicID uint) ([]domain.Role, error) {
	var roles []domain.Role
	// Busca as roles globais (ClinicID IS NULL) e as da clínica
	err := database.DB.Where("clinic_id IS NULL OR clinic_id = ?", clinicID).Order("id asc").Find(&roles).Error
	return roles, err
}

func (r *RoleRepository) Create(role *domain.Role) error {
	return database.DB.Create(role).Error
}

func (r *RoleRepository) Update(role *domain.Role) error {
	return database.DB.Save(role).Error
}

func (r *RoleRepository) Delete(id uint) error {
	return database.DB.Delete(&domain.Role{}, id).Error
}
