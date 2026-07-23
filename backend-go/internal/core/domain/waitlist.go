package domain

import (
	"time"
)

type Waitlist struct {
	ID       uint    `gorm:"primaryKey" json:"id"`
	ClinicID uint    `gorm:"not null" json:"clinic_id"`
	Clinic   *Clinic `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`

	PatientID uint     `gorm:"not null" json:"patient_id"`
	Patient   *Patient `gorm:"foreignKey:PatientID" json:"patient,omitempty"`

	DoctorID *uint `json:"doctor_id"` // Optional
	Doctor   *User `gorm:"foreignKey:DoctorID" json:"doctor,omitempty"`

	AppointmentTypeID *uint            `json:"appointment_type_id"` // Optional
	AppointmentType   *AppointmentType `gorm:"foreignKey:AppointmentTypeID" json:"appointment_type,omitempty"`

	PreferredDays      string `gorm:"type:text" json:"preferred_days"` // Array jsonado
	PreferredTimeRange string `gorm:"size:50" json:"preferred_time_range"`
	UrgencyLevel       string `gorm:"size:50" json:"urgency_level"`
	Notes              string `gorm:"type:text" json:"notes"`
	Status             string `gorm:"size:50;default:'pending'" json:"status"`

	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}
