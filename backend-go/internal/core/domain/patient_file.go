package domain

import (
	"time"

	"gorm.io/gorm"
)

type PatientFileType string

const (
	FileTypeRadiografia       PatientFileType = "RADIOGRAFIA"
	FileTypeFotoIntraoral     PatientFileType = "FOTO_INTRAORAL"
	FileTypeExameLaboratorial PatientFileType = "EXAME_LABORATORIAL"
	FileTypeOutro             PatientFileType = "OUTRO"
)

func ValidatePatientFileType(t PatientFileType) bool {
	switch t {
	case FileTypeRadiografia, FileTypeFotoIntraoral, FileTypeExameLaboratorial, FileTypeOutro:
		return true
	default:
		return false
	}
}

type PatientFile struct {
	ID          uint            `gorm:"primaryKey" json:"id"`
	ClinicID    uint            `gorm:"not null;index:idx_patient_files_patient" json:"clinic_id"`
	Clinic      *Clinic         `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`
	PatientID   uint            `gorm:"not null;index:idx_patient_files_patient" json:"patient_id"`
	Patient     *Patient        `gorm:"foreignKey:PatientID" json:"patient,omitempty"`
	FileName    string          `gorm:"column:file_name;size:255;not null" json:"file_name"`
	FileType    PatientFileType `gorm:"column:file_type;size:100;not null;default:'OUTRO'" json:"file_type"`
	FileURL     string          `gorm:"column:file_url;type:text;not null" json:"file_url"`
	SupabaseUrl string          `gorm:"column:supabase_url;size:500" json:"supabase_url,omitempty"`
	FileSize    int64           `gorm:"column:file_size;not null;default:0" json:"file_size"`
	UploadedBy  uint            `gorm:"column:uploaded_by;not null;default:0" json:"uploaded_by"`
	Category    string          `gorm:"size:100" json:"category,omitempty"`
	CreatedAt   time.Time       `json:"created_at"`
	UpdatedAt   time.Time       `json:"updated_at,omitempty"`
	DeletedAt   gorm.DeletedAt  `gorm:"index" json:"-"`
}

func (PatientFile) TableName() string {
	return "patient_files"
}

func (f *PatientFile) BeforeSave(tx *gorm.DB) (err error) {
	if f.FileURL == "" && f.SupabaseUrl != "" {
		f.FileURL = f.SupabaseUrl
	} else if f.SupabaseUrl == "" && f.FileURL != "" {
		f.SupabaseUrl = f.FileURL
	}
	return
}

func (f *PatientFile) AfterFind(tx *gorm.DB) (err error) {
	if f.FileURL == "" && f.SupabaseUrl != "" {
		f.FileURL = f.SupabaseUrl
	} else if f.SupabaseUrl == "" && f.FileURL != "" {
		f.SupabaseUrl = f.FileURL
	}
	return
}
