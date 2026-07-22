package domain

import (
	"time"

	"gorm.io/gorm"
)

type PatientFile struct {
	ID       uint    `gorm:"primaryKey" json:"id"`
	ClinicID uint    `gorm:"not null" json:"clinic_id"`
	Clinic   *Clinic `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`

	PatientID uint     `gorm:"not null" json:"patient_id"`
	Patient   *Patient `gorm:"foreignKey:PatientID" json:"patient,omitempty"`

	FileName string `gorm:"size:255;not null" json:"file_name"`
	FilePath string `gorm:"size:255;not null" json:"file_path"`
	FileType string `gorm:"size:100" json:"file_type"`
	Category string `gorm:"size:100" json:"category"`

	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"-"`
}
