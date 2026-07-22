package domain

import (
	"time"

	"gorm.io/gorm"
)

type Clinic struct {
	ID          uint           `gorm:"primaryKey" json:"id"`
	Name        string         `gorm:"size:255;not null" json:"name"`
	CNPJ        string         `gorm:"size:20;unique;not null" json:"cnpj"`
	Email       string         `gorm:"size:255" json:"email"`
	Phone       string         `gorm:"size:20" json:"phone"`
	Status      string         `gorm:"size:50;default:'trial'" json:"status"`
	Plan        string         `gorm:"size:50;default:'premium'" json:"plan"`
	TrialEndsAt *time.Time     `json:"trial_ends_at"`
	DeletedBy   *uint          `json:"deleted_by"`
	CreatedAt   time.Time      `json:"created_at"`
	UpdatedAt   time.Time      `json:"updated_at"`
	DeletedAt   gorm.DeletedAt `gorm:"index" json:"-"`
}
