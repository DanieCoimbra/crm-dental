package repositories

import (
	"encoding/json"

	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"gorm.io/gorm"
)

type UserRepository struct{}

func NewUserRepository() *UserRepository {
	return &UserRepository{}
}

func (r *UserRepository) Create(user *domain.User) error {
	return database.DB.Create(user).Error
}

func (r *UserRepository) FindByEmail(email string) (*domain.User, error) {
	var user domain.User
	err := database.DB.Preload("Role").Preload("Clinic").Where("email = ?", email).First(&user).Error
	if err != nil {
		return nil, err
	}
	if user.Role != nil && user.Role.Permissions != "" {
		json.Unmarshal([]byte(user.Role.Permissions), &user.PermissionsList)
	}
	return &user, nil
}

func (r *UserRepository) FindByID(id uint) (*domain.User, error) {
	var user domain.User
	err := database.DB.Preload("Role").Preload("Clinic").First(&user, id).Error
	if err != nil {
		return nil, err
	}
	if user.Role != nil && user.Role.Permissions != "" {
		json.Unmarshal([]byte(user.Role.Permissions), &user.PermissionsList)
	}
	return &user, nil
}

func (r *UserRepository) FindByIDAndClinic(id uint, clinicID uint) (*domain.User, error) {
	var user domain.User
	err := database.DB.Preload("Role").Preload("Clinic").Where("id = ? AND clinic_id = ?", id, clinicID).First(&user).Error
	if err != nil {
		return nil, err
	}
	if user.Role != nil && user.Role.Permissions != "" {
		json.Unmarshal([]byte(user.Role.Permissions), &user.PermissionsList)
	}
	return &user, nil
}

func (r *UserRepository) ListByRole(clinicID uint, roleName string) ([]domain.User, error) {
	var users []domain.User
	query := database.DB.Preload("Role").Preload("Clinic").Where("users.clinic_id = ?", clinicID)

	if roleName != "" {
		query = query.Joins("JOIN roles ON roles.id = users.role_id").Where("roles.name = ?", roleName)
	}

	err := query.Find(&users).Error
	for i := range users {
		if users[i].Role != nil && users[i].Role.Permissions != "" {
			json.Unmarshal([]byte(users[i].Role.Permissions), &users[i].PermissionsList)
		}
	}
	return users, err
}

func (r *UserRepository) Update(user *domain.User) error {
	return database.DB.Save(user).Error
}

func (r *UserRepository) Delete(id uint, clinicID uint, deletedBy uint) error {
	return database.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&domain.User{}).Where("id = ? AND clinic_id = ?", id, clinicID).Update("deleted_by", deletedBy).Error; err != nil {
			return err
		}
		return tx.Where("id = ? AND clinic_id = ?", id, clinicID).Delete(&domain.User{}).Error
	})
}
