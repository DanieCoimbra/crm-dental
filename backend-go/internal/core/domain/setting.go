package domain

import (
	"time"
)

type Setting struct {
	ID       uint    `gorm:"primaryKey" json:"id"`
	ClinicID uint    `gorm:"not null" json:"clinic_id"`
	Clinic   *Clinic `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`
	Key      string  `gorm:"size:100;not null" json:"key"`
	Value    string  `gorm:"type:text" json:"value"` // Stored as JSON string if array/object

	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}
