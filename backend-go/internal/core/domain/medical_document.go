package domain

import (
	"strings"
	"time"

	"dental-crm-api/internal/pkg/encryption"

	"gorm.io/gorm"
)

const (
	DocumentTypeAtestado       = "ATESTADO"
	DocumentTypeReceita        = "RECEITA"
	DocumentTypeEncaminhamento = "ENCAMINHAMENTO"
)

type MedicalDocument struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	ClinicID  uint      `gorm:"not null;index:idx_medical_docs_patient" json:"clinic_id"`
	Clinic    *Clinic   `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`
	PatientID uint      `gorm:"not null;index:idx_medical_docs_patient" json:"patient_id"`
	Patient   *Patient  `gorm:"foreignKey:PatientID" json:"patient,omitempty"`
	DentistID uint      `gorm:"column:dentist_id;not null" json:"dentist_id"`
	Dentist   *User     `gorm:"foreignKey:DentistID" json:"dentist,omitempty"`
	Type      string    `gorm:"column:type;type:varchar(50);not null" json:"type"`
	Title     string    `gorm:"column:title;type:varchar(255);not null" json:"title"`
	Content   string    `gorm:"column:content_encrypted;type:text;not null" json:"content"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

func (MedicalDocument) TableName() string {
	return "medical_documents"
}

// BeforeSave encrypts content before persisting to DB
func (m *MedicalDocument) BeforeSave(tx *gorm.DB) (err error) {
	if m.Content != "" && !strings.HasPrefix(m.Content, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(m.Content)
		if err == nil {
			m.Content = encrypted
		}
	}
	return
}

// AfterSave decrypts content back into memory after saving
func (m *MedicalDocument) AfterSave(tx *gorm.DB) (err error) {
	return m.AfterFind(tx)
}

// AfterFind decrypts content when fetched from DB
func (m *MedicalDocument) AfterFind(tx *gorm.DB) (err error) {
	if m.Content != "" {
		decrypted, err := encryption.Decrypt(m.Content)
		if err == nil {
			m.Content = decrypted
		}
	}
	return
}
