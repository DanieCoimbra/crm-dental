package domain

import (
	"time"

	"github.com/google/uuid"
)

type Clinic struct {
	ID          uuid.UUID `gorm:"type:uuid;primaryKey;default:gen_random_uuid()" json:"id"`
	Name        string    `gorm:"type:varchar(255);not null" json:"name"`
	CnpjCpf     *string   `gorm:"type:varchar(20)" json:"cnpj_cpf"`
	Phone       *string   `gorm:"type:varchar(20)" json:"phone"`
	Email       string    `gorm:"type:varchar(255);not null;unique" json:"email"`
	TrialEndsAt time.Time `gorm:"type:timestamptz;not null" json:"trial_ends_at"`
	Status      string    `gorm:"type:varchar(20);not null;default:'active'" json:"status"`
	CreatedAt   time.Time `gorm:"type:timestamptz;default:now()" json:"created_at"`
	UpdatedAt   time.Time `gorm:"type:timestamptz;default:now()" json:"updated_at"`
}

type User struct {
	ID           uuid.UUID `gorm:"type:uuid;primaryKey" json:"id"`
	ClinicID     uuid.UUID `gorm:"type:uuid;not null;index" json:"clinic_id"`
	Name         string    `gorm:"type:varchar(255);not null" json:"name"`
	Email        string    `gorm:"type:varchar(255);not null;unique" json:"email"`
	PasswordHash string    `gorm:"type:varchar(255);not null" json:"-"`
	Role         string    `gorm:"type:varchar(20);not null;index" json:"role"`
	AvatarURL    *string   `gorm:"type:text" json:"avatar_url"`
	IsActive     bool      `gorm:"type:boolean;not null;default:true" json:"is_active"`
	CreatedAt    time.Time `gorm:"type:timestamptz;default:now()" json:"created_at"`
	UpdatedAt    time.Time `gorm:"type:timestamptz;default:now()" json:"updated_at"`

	Clinic *Clinic `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`
}
