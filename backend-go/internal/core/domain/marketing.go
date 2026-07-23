package domain

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type ReferralPartner struct {
	ID             uuid.UUID      `json:"id" gorm:"type:uuid;default:uuid_generate_v4();primaryKey"`
	ClinicID       uint           `json:"clinic_id" gorm:"not null"`
	Name           string         `json:"name" gorm:"not null"`
	Email          string         `json:"email"`
	Phone          string         `json:"phone"`
	CommissionRate float64        `json:"commission_rate"` // e.g. 10.0 for 10%
	PixKey         string         `json:"pix_key"`         // Para pagamentos de comissão
	CreatedAt      time.Time      `json:"created_at"`
	UpdatedAt      time.Time      `json:"updated_at"`
	DeletedAt      gorm.DeletedAt `gorm:"index" json:"-"`
}

type PromoCode struct {
	ID            uuid.UUID      `json:"id" gorm:"type:uuid;default:uuid_generate_v4();primaryKey"`
	ClinicID      uint           `json:"clinic_id" gorm:"not null;uniqueIndex:idx_clinic_promo"`
	PartnerID     *uuid.UUID     `json:"partner_id" gorm:"type:uuid"`
	Code          string         `json:"code" gorm:"not null;uniqueIndex:idx_clinic_promo"`
	DiscountType  string         `json:"discount_type"`  // "percentage" ou "fixed"
	DiscountValue float64        `json:"discount_value"` // valor monetário ou %
	MaxUses       int            `json:"max_uses"`       // 0 para ilimitado
	UsedCount     int            `json:"used_count" gorm:"default:0"`
	ExpiresAt     *time.Time     `json:"expires_at"`
	IsActive      bool           `json:"is_active" gorm:"default:true"`
	CreatedAt     time.Time      `json:"created_at"`
	UpdatedAt     time.Time      `json:"updated_at"`
	DeletedAt     gorm.DeletedAt `gorm:"index" json:"-"`

	Partner *ReferralPartner `json:"partner,omitempty" gorm:"foreignKey:PartnerID"`
}
