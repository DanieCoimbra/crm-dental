package domain

import (
	"time"

	"gorm.io/gorm"
)

type User struct {
	ID              uint   `gorm:"primaryKey" json:"id"`
	Name            string `gorm:"size:255;not null" json:"name"`
	Email           string `gorm:"size:255;unique;not null" json:"email"`
	Password        string `gorm:"not null" json:"-"` // Omitido no JSON por segurança
	CPF             string `gorm:"size:20" json:"cpf"`
	Phone           string `gorm:"size:20" json:"phone"`
	Address         string `gorm:"type:text" json:"address"`
	MedicalRegistry string `gorm:"size:50" json:"medical_registry"`
	CTPS            string `gorm:"size:50" json:"ctps"`
	Avatar          string `gorm:"size:255" json:"avatar"`

	// Relacionamentos
	ClinicID uint    `gorm:"not null" json:"clinic_id"`
	Clinic   *Clinic `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`

	CurrentRoomID *uint `json:"current_room_id"`

	RoleID *uint `json:"role_id"`
	Role   *Role `gorm:"foreignKey:RoleID" json:"role,omitempty"`

	PermissionsList []string `gorm:"-" json:"permissions_list"`

	ThemePreference string `gorm:"default:'system'" json:"theme_preference"`

	FailedAttempts int        `gorm:"default:0" json:"failed_attempts"`
	LockedUntil    *time.Time `json:"locked_until,omitempty"`

	DeletedBy *uint `json:"deleted_by"`

	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"-"`
}
