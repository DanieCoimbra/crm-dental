package domain

import (
	"strings"
	"time"

	"dental-crm-api/internal/pkg/encryption"
	"gorm.io/gorm"
)

type Patient struct {
	ID              uint       `gorm:"primaryKey" json:"id"`
	ClinicID        uint       `gorm:"not null;index:idx_patients_clinic_id" json:"clinic_id"`
	Clinic          *Clinic    `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`
	Name            string     `gorm:"column:full_name;size:150;not null" json:"name"`
	FullName        string     `gorm:"-" json:"full_name,omitempty"`
	CPF             string     `gorm:"column:cpf_encrypted;type:text;not null" json:"cpf"`
	Email           string     `gorm:"size:150" json:"email"`
	Phone           string     `gorm:"column:phone_encrypted;type:text;not null" json:"phone"`
	Cep             string     `gorm:"size:20" json:"cep,omitempty"`
	Street          string     `gorm:"size:255" json:"street,omitempty"`
	Neighborhood    string     `gorm:"size:255" json:"neighborhood,omitempty"`
	Number          string     `gorm:"size:50" json:"number,omitempty"`
	HealthInsurance string     `gorm:"column:health_insurance;size:100" json:"health_insurance"`
	BirthDate       *time.Time `gorm:"column:birth_date;type:date" json:"birth_date"`
	MedicalHistory  string     `gorm:"type:text" json:"medical_history,omitempty"`
	Notes           string     `gorm:"column:notes_encrypted;type:text" json:"notes"`
	Weight          float64    `json:"weight,omitempty"`

	DeletedBy *uint `json:"deleted_by,omitempty"`

	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"-"`
}

func (Patient) TableName() string {
	return "patients"
}

// BeforeSave encrypts sensitive fields before saving to DB
func (p *Patient) BeforeSave(tx *gorm.DB) (err error) {
	if p.CPF != "" && !strings.HasPrefix(p.CPF, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(p.CPF)
		if err == nil {
			p.CPF = encrypted
		}
	}
	if p.Phone != "" && !strings.HasPrefix(p.Phone, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(p.Phone)
		if err == nil {
			p.Phone = encrypted
		}
	}
	if p.Notes != "" && !strings.HasPrefix(p.Notes, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(p.Notes)
		if err == nil {
			p.Notes = encrypted
		}
	}
	if p.MedicalHistory != "" && !strings.HasPrefix(p.MedicalHistory, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(p.MedicalHistory)
		if err == nil {
			p.MedicalHistory = encrypted
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
	p.FullName = p.Name
	if p.CPF != "" {
		decrypted, err := encryption.Decrypt(p.CPF)
		if err == nil {
			p.CPF = decrypted
		}
	}
	if p.Phone != "" {
		decrypted, err := encryption.Decrypt(p.Phone)
		if err == nil {
			p.Phone = decrypted
		}
	}
	if p.Notes != "" {
		decrypted, err := encryption.Decrypt(p.Notes)
		if err == nil {
			p.Notes = decrypted
		}
	}
	if p.MedicalHistory != "" {
		decrypted, err := encryption.Decrypt(p.MedicalHistory)
		if err == nil {
			p.MedicalHistory = decrypted
		}
	}
	return
}
