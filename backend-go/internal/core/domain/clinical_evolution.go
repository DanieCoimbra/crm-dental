package domain

import (
	"strings"
	"time"

	"dental-crm-api/internal/pkg/encryption"
	"gorm.io/gorm"
)

type ClinicalEvolution struct {
	ID       uint    `gorm:"primaryKey" json:"id"`
	ClinicID uint    `gorm:"not null" json:"clinic_id"`
	Clinic   *Clinic `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`

	PatientID uint     `gorm:"not null" json:"patient_id"`
	Patient   *Patient `gorm:"foreignKey:PatientID" json:"patient,omitempty"`

	UserID uint  `gorm:"not null" json:"user_id"`
	User   *User `gorm:"foreignKey:UserID" json:"user,omitempty"`

	Content string `gorm:"type:text;not null" json:"content"` // Encrypted no PHP

	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"-"`
}

func (e *ClinicalEvolution) BeforeSave(tx *gorm.DB) (err error) {
	if e.Content != "" && !strings.HasPrefix(e.Content, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(e.Content)
		if err == nil {
			e.Content = encrypted
		}
	}
	return
}

func (e *ClinicalEvolution) AfterSave(tx *gorm.DB) (err error) {
	return e.AfterFind(tx)
}

func (e *ClinicalEvolution) AfterFind(tx *gorm.DB) (err error) {
	if e.Content != "" {
		decrypted, err := encryption.Decrypt(e.Content)
		if err == nil {
			e.Content = decrypted
		}
	}
	return
}
