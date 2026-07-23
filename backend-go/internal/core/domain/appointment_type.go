package domain

import (
	"time"

	"gorm.io/gorm"
)

type AppointmentType struct {
	ID              uint    `gorm:"primaryKey" json:"id"`
	ClinicID        uint    `gorm:"not null" json:"clinic_id"`
	Clinic          *Clinic `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`
	Name            string  `gorm:"size:100;not null" json:"name"`
	Description     string  `gorm:"type:text" json:"description"`
	DurationMinutes int     `gorm:"default:30" json:"duration_minutes"`
	Color           string  `gorm:"size:20;default:'#3788d8'" json:"color"`

	DeletedBy *uint          `json:"deleted_by"`
	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"-"`

	Materials []ProcedureMaterial `gorm:"foreignKey:AppointmentTypeID;constraint:OnDelete:CASCADE;" json:"materials,omitempty"`
}

type ProcedureMaterial struct {
	ID                uint           `gorm:"primaryKey" json:"id"`
	AppointmentTypeID uint           `gorm:"not null;index" json:"appointment_type_id"`
	InventoryItemID   uint           `gorm:"not null;index" json:"inventory_item_id"`
	InventoryItem     *InventoryItem `gorm:"foreignKey:InventoryItemID" json:"item,omitempty"`
	Quantity          float64        `gorm:"type:numeric(10,2);not null" json:"quantity"`
}
