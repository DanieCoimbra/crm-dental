package domain

import (
	"strings"
	"time"

	"dental-crm-api/internal/pkg/encryption"
	"gorm.io/gorm"
)

type ClinicalNote struct {
	ID               uint      `gorm:"primaryKey" json:"id"`
	ClinicID         uint      `gorm:"not null;index:idx_clinical_notes_patient" json:"clinic_id"`
	Clinic           *Clinic   `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`
	PatientID        uint      `gorm:"not null;index:idx_clinical_notes_patient" json:"patient_id"`
	Patient          *Patient  `gorm:"foreignKey:PatientID" json:"patient,omitempty"`
	DentistID        uint      `gorm:"column:dentist_id;not null" json:"dentist_id"`
	Dentist          *User     `gorm:"foreignKey:DentistID" json:"dentist,omitempty"`
	AttendanceDate   time.Time `gorm:"column:attendance_date;not null" json:"attendance_date"`
	ChiefComplaint   string    `gorm:"column:chief_complaint_encrypted;type:text" json:"chief_complaint"`
	Diagnosis        string    `gorm:"column:diagnosis_encrypted;type:text" json:"diagnosis"`
	ProcedureSummary string    `gorm:"column:procedure_summary;type:text;not null" json:"procedure_summary"`
	CreatedAt        time.Time `json:"created_at"`
}

func (ClinicalNote) TableName() string {
	return "clinical_notes"
}

// BeforeSave encrypts chief_complaint and diagnosis before persisting to DB
func (c *ClinicalNote) BeforeSave(tx *gorm.DB) (err error) {
	if c.ChiefComplaint != "" && !strings.HasPrefix(c.ChiefComplaint, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(c.ChiefComplaint)
		if err == nil {
			c.ChiefComplaint = encrypted
		}
	}
	if c.Diagnosis != "" && !strings.HasPrefix(c.Diagnosis, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(c.Diagnosis)
		if err == nil {
			c.Diagnosis = encrypted
		}
	}
	return
}

// AfterSave decrypts fields back into memory after saving
func (c *ClinicalNote) AfterSave(tx *gorm.DB) (err error) {
	return c.AfterFind(tx)
}

// AfterFind decrypts chief_complaint and diagnosis when fetched from DB
func (c *ClinicalNote) AfterFind(tx *gorm.DB) (err error) {
	if c.ChiefComplaint != "" {
		decrypted, err := encryption.Decrypt(c.ChiefComplaint)
		if err == nil {
			c.ChiefComplaint = decrypted
		}
	}
	if c.Diagnosis != "" {
		decrypted, err := encryption.Decrypt(c.Diagnosis)
		if err == nil {
			c.Diagnosis = decrypted
		}
	}
	return
}
