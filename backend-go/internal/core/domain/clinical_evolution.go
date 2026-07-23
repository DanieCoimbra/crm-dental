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

	ContentHtml string `gorm:"type:text;not null;column:content_html" json:"content_html"` // Encrypted no PHP

	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"-"`
}

func (e *ClinicalEvolution) BeforeSave(tx *gorm.DB) (err error) {
	if e.ContentHtml != "" && !strings.HasPrefix(e.ContentHtml, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(e.ContentHtml)
		if err == nil {
			e.ContentHtml = encrypted
		}
	}
	return
}

func (e *ClinicalEvolution) AfterSave(tx *gorm.DB) (err error) {
	return e.AfterFind(tx)
}

func (e *ClinicalEvolution) AfterFind(tx *gorm.DB) (err error) {
	if e.ContentHtml != "" {
		decrypted, err := encryption.Decrypt(e.ContentHtml)
		if err == nil {
			e.ContentHtml = decrypted
		}
	}
	return
}
