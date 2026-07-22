package domain

import (
	"time"

	"gorm.io/gorm"
)

type Affiliate struct {
	ID            uint           `gorm:"primaryKey" json:"id"`
	Name          string         `gorm:"size:255;not null" json:"name"`
	Code          string         `gorm:"size:50;uniqueIndex;not null" json:"code"`
	CommissionPct float64        `gorm:"type:numeric(5,2);default:0" json:"commission_pct"`
	Balance       float64        `gorm:"type:numeric(15,2);default:0" json:"balance"` // Em centavos
	CreatedAt     time.Time      `json:"created_at"`
	UpdatedAt     time.Time      `json:"updated_at"`
	DeletedAt     gorm.DeletedAt `gorm:"index" json:"-"`
}

type Coupon struct {
	ID            uint           `gorm:"primaryKey" json:"id"`
	Code          string         `gorm:"size:50;uniqueIndex;not null" json:"code"`
	DiscountType  string         `gorm:"size:20;not null" json:"discount_type"` // "percentage" or "fixed"
	DiscountValue float64        `gorm:"type:numeric(10,2);not null" json:"discount_value"`
	AffiliateID   *uint          `gorm:"index" json:"affiliate_id"`
	Affiliate     *Affiliate     `gorm:"foreignKey:AffiliateID" json:"affiliate,omitempty"`
	MaxUses       int            `gorm:"default:0" json:"max_uses"` // 0 = unlimited
	UsesCount     int            `gorm:"default:0" json:"uses_count"`
	ExpiresAt     *time.Time     `json:"expires_at"`
	CreatedAt     time.Time      `json:"created_at"`
	UpdatedAt     time.Time      `json:"updated_at"`
	DeletedAt     gorm.DeletedAt `gorm:"index" json:"-"`
}
