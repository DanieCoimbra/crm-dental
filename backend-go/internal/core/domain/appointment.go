package domain

import (
	"strings"
	"time"

	"dental-crm-api/internal/pkg/encryption"
	"gorm.io/gorm"
)

type Appointment struct {
	ID       uint    `gorm:"primaryKey" json:"id"`
	ClinicID uint    `gorm:"not null" json:"clinic_id"`
	Clinic   *Clinic `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`

	DoctorID uint  `gorm:"not null" json:"doctor_id"`
	Doctor   *User `gorm:"foreignKey:DoctorID" json:"doctor,omitempty"`

	PatientID uint     `gorm:"not null" json:"patient_id"`
	Patient   *Patient `gorm:"foreignKey:PatientID" json:"patient,omitempty"`

	RoomID *uint `json:"room_id"`
	Room   *Room `gorm:"foreignKey:RoomID" json:"room,omitempty"`

	AppointmentTypeID *uint            `json:"appointment_type_id"`
	AppointmentType   *AppointmentType `gorm:"foreignKey:AppointmentTypeID" json:"appointment_type,omitempty"`

	StartTime *time.Time `json:"start_time"`
	EndTime   *time.Time `json:"end_time"`

	Notes  string `gorm:"type:text" json:"notes"` // Encrypted no PHP
	Status string `gorm:"size:50;default:'scheduled'" json:"status"`

	ActualStartTime *time.Time `json:"actual_start_time"`
	ActualEndTime   *time.Time `json:"actual_end_time"`

	DeletedBy *uint `json:"deleted_by"`

	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"-"`
}

// BeforeSave encrypts sensitive fields before saving to DB
func (a *Appointment) BeforeSave(tx *gorm.DB) (err error) {
	if a.Notes != "" && !strings.HasPrefix(a.Notes, "eyJpdiI6") {
		encrypted, err := encryption.Encrypt(a.Notes)
		if err == nil {
			a.Notes = encrypted
		}
	}
	return
}

// AfterSave decrypts sensitive fields back to plain text for the struct in memory
func (a *Appointment) AfterSave(tx *gorm.DB) (err error) {
	return a.AfterFind(tx)
}

// AfterFind decrypts sensitive fields after reading from DB
func (a *Appointment) AfterFind(tx *gorm.DB) (err error) {
	if a.Notes != "" {
		decrypted, err := encryption.Decrypt(a.Notes)
		if err == nil {
			a.Notes = decrypted
		}
	}
	return
}
