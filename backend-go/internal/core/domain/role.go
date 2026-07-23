package domain

import (
	"time"
)

type Role struct {
	ID          uint      `gorm:"primaryKey" json:"id"`
	Name        string    `gorm:"size:50;not null" json:"name"`
	Permissions string    `gorm:"type:text;default:'[]'" json:"permissions"` // Armazena array JSON de strings
	ClinicID    *uint     `json:"clinic_id"`                                 // Se nulo, é uma role global do sistema (ex: admin, owner)
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}
