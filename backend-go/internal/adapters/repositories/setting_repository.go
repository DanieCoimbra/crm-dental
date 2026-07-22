package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
)

type SettingRepository struct{}

func NewSettingRepository() *SettingRepository {
	return &SettingRepository{}
}

func (r *SettingRepository) GetValue(clinicID uint, key string, defaultValue string) string {
	var setting domain.Setting
	err := database.DB.Where("clinic_id = ? AND key = ?", clinicID, key).First(&setting).Error
	if err != nil {
		return defaultValue
	}
	return setting.Value
}

func (r *SettingRepository) SetValue(clinicID uint, key string, value string) error {
	var setting domain.Setting
	err := database.DB.Where("clinic_id = ? AND key = ?", clinicID, key).First(&setting).Error
	if err != nil {
		// Create
		return database.DB.Create(&domain.Setting{
			ClinicID: clinicID,
			Key:      key,
			Value:    value,
		}).Error
	}
	// Update
	setting.Value = value
	return database.DB.Save(&setting).Error
}

func (r *SettingRepository) ListByClinic(clinicID uint) ([]domain.Setting, error) {
	var settings []domain.Setting
	err := database.DB.Where("clinic_id = ?", clinicID).Find(&settings).Error
	return settings, err
}
