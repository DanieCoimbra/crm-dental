package domain

import (
	"time"

	"gorm.io/gorm"
)

type Room struct {
	ID       uint    `gorm:"primaryKey" json:"id"`
	ClinicID uint    `gorm:"not null" json:"clinic_id"`
	Clinic   *Clinic `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`
	Name        string  `gorm:"size:100;not null" json:"name"`
	Description string  `gorm:"type:text" json:"description"`
	IsActive    bool    `gorm:"default:true" json:"is_active"`

	DeletedBy *uint `json:"deleted_by"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"-"`
}
