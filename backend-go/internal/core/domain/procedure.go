package domain

import (
	"time"

	"gorm.io/gorm"
)

type Procedure struct {
	ID              uint           `gorm:"primaryKey" json:"id"`
	ClinicID        uint           `gorm:"index;not null" json:"clinic_id"`
	Name            string         `gorm:"size:150;not null" json:"name"`
	Description     string         `json:"description"`
	BasePriceCents  int64          `gorm:"not null" json:"base_price_cents"`
	DurationMinutes int            `gorm:"default:30" json:"duration_minutes"`
	Color           string         `gorm:"size:20;default:'#2563EB'" json:"color"`
	CreatedAt       time.Time      `json:"created_at"`
	UpdatedAt       time.Time      `json:"updated_at"`
	DeletedAt       gorm.DeletedAt `gorm:"index" json:"deleted_at,omitempty"`
}
