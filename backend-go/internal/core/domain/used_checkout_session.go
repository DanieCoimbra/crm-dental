package domain

import "time"

type UsedCheckoutSession struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	SessionID string    `gorm:"size:255;not null;unique" json:"session_id"`
	ClinicID  uint      `gorm:"not null" json:"clinic_id"`
	CreatedAt time.Time `json:"created_at"`
}
