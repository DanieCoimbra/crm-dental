package domain

import (
	"strings"
	"time"

	"dental-crm-api/internal/pkg/encryption"
	"gorm.io/gorm"
)

type Patient struct {
	ID              uint           `gorm:"primaryKey" json:"id"`
	ClinicID        uint           `gorm:"not null" json:"clinic_id"`
	Clinic          *Clinic        `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`
	Name            string         `gorm:"size:255;not null" json:"name"`
	CPF             string         `gorm:"size:20" json:"cpf"`
	Email           string         `gorm:"size:255" json:"email"`
	Phone           string         `gorm:"size:20" json:"phone"`
	Cep             string         `gorm:"size:20" json:"cep"`
	Street          string         `gorm:"size:255" json:"street"`
	Neighborhood    string         `gorm:"size:255" json:"neighborhood"`
	Number          string         `gorm:"size:50" json:"number"`
	HealthInsurance string         `gorm:"size:100" json:"health_insurance"`
	BirthDate       *time.Time     `gorm:"type:date" json:"birth_date"`
	MedicalHistory  string         `gorm:"type:text" json:"medical_history"` // Encrypted no PHP
	Notes           string         `gorm:"type:text" json:"notes"`           // Encrypted no PHP
	Weight          float64        `json:"weight"`

	DeletedBy *uint `json:"deleted_by"`

	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"-"`
}

// BeforeSave encryts sensitive fields before saving to DB
func (p *Patient) BeforeSave(tx *gorm.DB) (err error) {
	if p.MedicalHistory != "" && !strings.HasPrefix(p.MedicalHistory, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(p.MedicalHistory)
		if err == nil {
			p.MedicalHistory = encrypted
		}
	}
	if p.Notes != "" && !strings.HasPrefix(p.Notes, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(p.Notes)
		if err == nil {
			p.Notes = encrypted
		}
	}
	return
}

// AfterSave decrypts sensitive fields back to plain text for the struct in memory
func (p *Patient) AfterSave(tx *gorm.DB) (err error) {
	return p.AfterFind(tx)
}

// AfterFind decrypts sensitive fields after reading from DB
func (p *Patient) AfterFind(tx *gorm.DB) (err error) {
	if p.MedicalHistory != "" {
		decrypted, err := encryption.Decrypt(p.MedicalHistory)
		if err == nil {
			p.MedicalHistory = decrypted
		}
	}
	if p.Notes != "" {
		decrypted, err := encryption.Decrypt(p.Notes)
		if err == nil {
			p.Notes = decrypted
		}
	}
	return
}
