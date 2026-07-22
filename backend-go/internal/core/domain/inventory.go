package domain

import (
	"time"

	"gorm.io/gorm"
)

type InventoryItem struct {
	ID          uint           `gorm:"primaryKey" json:"id"`
	ClinicID    uint           `gorm:"not null;index" json:"clinic_id"`
	Clinic      *Clinic        `gorm:"foreignKey:ClinicID" json:"-"`
	Name        string         `gorm:"size:255;not null" json:"name"`
	SKU         string         `gorm:"size:100" json:"sku"`
	Quantity    float64        `gorm:"type:numeric(10,2);default:0" json:"quantity"`
	MinQuantity float64        `gorm:"type:numeric(10,2);default:0" json:"min_quantity"`
	Unit        string         `gorm:"size:50" json:"unit"` // e.g., Unidade, Caixa, ML
	CreatedAt   time.Time      `json:"created_at"`
	UpdatedAt   time.Time      `json:"updated_at"`
	DeletedAt   gorm.DeletedAt `gorm:"index" json:"-"`
}

type InventoryTransaction struct {
	ID              uint           `gorm:"primaryKey" json:"id"`
	ClinicID        uint           `gorm:"not null;index" json:"clinic_id"`
	InventoryItemID uint           `gorm:"not null;index" json:"inventory_item_id"`
	InventoryItem   *InventoryItem `gorm:"foreignKey:InventoryItemID" json:"item,omitempty"`
	Type            string         `gorm:"size:10;not null" json:"type"` // "in" or "out"
	Quantity        float64        `gorm:"type:numeric(10,2);not null" json:"quantity"`
	Notes           string         `gorm:"type:text" json:"notes"`
	Date            time.Time      `json:"date"`
	CreatedAt       time.Time      `json:"created_at"`
}
