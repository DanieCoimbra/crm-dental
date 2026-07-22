package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"gorm.io/gorm"
)

type RoomRepository struct{}

func NewRoomRepository() *RoomRepository {
	return &RoomRepository{}
}

func (r *RoomRepository) Create(room *domain.Room) error {
	return database.DB.Create(room).Error
}

func (r *RoomRepository) Update(room *domain.Room) error {
	return database.DB.Save(room).Error
}

func (r *RoomRepository) Delete(id uint, clinicID uint, deletedBy uint) error {
	return database.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&domain.Room{}).Where("id = ? AND clinic_id = ?", id, clinicID).Update("deleted_by", deletedBy).Error; err != nil {
			return err
		}
		return tx.Where("id = ? AND clinic_id = ?", id, clinicID).Delete(&domain.Room{}).Error
	})
}

func (r *RoomRepository) FindByID(id, clinicID uint) (*domain.Room, error) {
	var room domain.Room
	err := database.DB.Where("id = ? AND clinic_id = ?", id, clinicID).First(&room).Error
	return &room, err
}

func (r *RoomRepository) ListByClinic(clinicID uint) ([]domain.Room, error) {
	var rooms []domain.Room
	err := database.DB.Where("clinic_id = ?", clinicID).Order("name asc").Find(&rooms).Error
	return rooms, err
}
