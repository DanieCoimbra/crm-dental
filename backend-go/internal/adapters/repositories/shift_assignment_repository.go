package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
)

type ShiftAssignmentRepository struct{}

func NewShiftAssignmentRepository() *ShiftAssignmentRepository {
	return &ShiftAssignmentRepository{}
}

func (r *ShiftAssignmentRepository) Create(shift *domain.ShiftAssignment) error {
	return database.DB.Create(shift).Error
}

func (r *ShiftAssignmentRepository) ListByClinic(clinicID uint) ([]domain.ShiftAssignment, error) {
	var shifts []domain.ShiftAssignment
	err := database.DB.Preload("Doctor").Preload("Room").Where("clinic_id = ?", clinicID).Order("date desc").Find(&shifts).Error
	return shifts, err
}

func (r *ShiftAssignmentRepository) Delete(id uint, clinicID uint) error {
	return database.DB.Where("id = ? AND clinic_id = ?", id, clinicID).Delete(&domain.ShiftAssignment{}).Error
}
