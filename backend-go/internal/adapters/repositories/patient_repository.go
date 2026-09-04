package repositories

import (
	"time"

	"gorm.io/gorm"

	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
)

type PatientRepository struct{}

func NewPatientRepository() *PatientRepository {
	return &PatientRepository{}
}

func (r *PatientRepository) Create(patient *domain.Patient) error {
	return database.DB.Create(patient).Error
}

func (r *PatientRepository) Update(patient *domain.Patient) error {
	return database.DB.Save(patient).Error
}

func (r *PatientRepository) Delete(id uint, clinicID uint, deletedBy uint) error {
	// Soft delete manual para registrar quem apagou com isolamento por clinic_id
	return database.DB.Model(&domain.Patient{}).Where("id = ? AND clinic_id = ?", id, clinicID).Updates(map[string]interface{}{
		"deleted_by": deletedBy,
		"deleted_at": gorm.DeletedAt{Time: time.Now(), Valid: true},
	}).Error
}

func (r *PatientRepository) FindByID(id uint, clinicID uint) (*domain.Patient, error) {
	var patient domain.Patient
	err := database.DB.Where("id = ? AND clinic_id = ?", id, clinicID).First(&patient).Error
	if err != nil {
		return nil, err
	}
	return &patient, nil
}

func (r *PatientRepository) ListByClinic(clinicID uint, search string, page int) ([]domain.Patient, error) {
	var patients []domain.Patient
	query := database.DB.Where("clinic_id = ?", clinicID)

	if search != "" {
		query = query.Where("LOWER(full_name) LIKE LOWER(?) OR LOWER(cpf_encrypted) LIKE LOWER(?)", "%"+search+"%", "%"+search+"%")
	}

	if page > 0 {
		limit := 15
		offset := (page - 1) * limit
		query = query.Limit(limit).Offset(offset)
	}

	err := query.Order("full_name asc, id asc").Find(&patients).Error
	return patients, err
}
