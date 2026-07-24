package domain

import (
	"time"

	"gorm.io/gorm"
)

type Subscription struct {
	ID                   uint       `gorm:"primaryKey" json:"id"`
	ClinicID             uint       `gorm:"not null;unique" json:"clinic_id"`                  // 1:1 relation
	Status               string     `gorm:"size:50;not null;default:'trialing'" json:"status"` // active, past_due, trialing, canceled
	Plan                 string     `gorm:"size:50;not null;default:'monthly'" json:"plan"`
	StripeCustomerID     string     `gorm:"size:255" json:"stripe_customer_id,omitempty"`
	StripeSubscriptionID string     `gorm:"size:255" json:"stripe_subscription_id,omitempty"`
	CurrentPeriodEnd     time.Time  `json:"current_period_end"`
	TrialEndsAt          time.Time  `json:"trial_ends_at"`
	GracePeriodEndsAt    *time.Time `json:"grace_period_ends_at,omitempty"`

	CouponID *uint   `json:"coupon_id,omitempty"`
	Coupon   *Coupon `gorm:"foreignKey:CouponID" json:"coupon,omitempty"`

	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"-"`
}
