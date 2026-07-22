package domain

import (
	"time"
)

type ShiftAssignment struct {
	ID       uint    `gorm:"primaryKey" json:"id"`
	ClinicID uint    `gorm:"not null" json:"clinic_id"`
	Clinic   *Clinic `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`

	DoctorID uint  `gorm:"not null" json:"doctor_id"`
	Doctor   *User `gorm:"foreignKey:DoctorID" json:"doctor,omitempty"`

	RoomID uint  `gorm:"not null" json:"room_id"`
	Room   *Room `gorm:"foreignKey:RoomID" json:"room,omitempty"`

	Date  *time.Time `gorm:"type:date" json:"date"`
	Shift string     `gorm:"size:50;not null" json:"shift"` // morning, afternoon

	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}
