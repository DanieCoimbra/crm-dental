package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"gorm.io/gorm"
)

type AppointmentTypeRepository struct{}

func NewAppointmentTypeRepository() *AppointmentTypeRepository {
	return &AppointmentTypeRepository{}
}

func (r *AppointmentTypeRepository) ListByClinic(clinicID uint) ([]domain.AppointmentType, error) {
	var types []domain.AppointmentType
	err := database.DB.Preload("Materials").Preload("Materials.InventoryItem").Where("clinic_id = ?", clinicID).Order("name asc").Find(&types).Error
	return types, err
}

func (r *AppointmentTypeRepository) Create(apptType *domain.AppointmentType) error {
	return database.DB.Create(apptType).Error
}

func (r *AppointmentTypeRepository) Delete(id uint, clinicID uint, deletedBy uint) error {
	return database.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&domain.AppointmentType{}).Where("id = ? AND clinic_id = ?", id, clinicID).Update("deleted_by", deletedBy).Error; err != nil {
			return err
		}
		return tx.Where("id = ? AND clinic_id = ?", id, clinicID).Delete(&domain.AppointmentType{}).Error
	})
}

func (r *AppointmentTypeRepository) FindByID(id, clinicID uint) (*domain.AppointmentType, error) {
	var apptType domain.AppointmentType
	err := database.DB.Preload("Materials").Preload("Materials.InventoryItem").Where("id = ? AND clinic_id = ?", id, clinicID).First(&apptType).Error
	return &apptType, err
}

func (r *AppointmentTypeRepository) Update(apptType *domain.AppointmentType) error {
	return database.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("appointment_type_id = ?", apptType.ID).Delete(&domain.ProcedureMaterial{}).Error; err != nil {
			return err
		}
		return tx.Save(apptType).Error
	})
}
