package repositories

import (
	"dental-crm-api/internal/core/domain"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type MarketingRepository struct {
	DB *gorm.DB
}

func NewMarketingRepository(db *gorm.DB) *MarketingRepository {
	return &MarketingRepository{DB: db}
}

// PromoCodes
func (r *MarketingRepository) CreatePromoCode(code *domain.PromoCode) error {
	return r.DB.Create(code).Error
}

func (r *MarketingRepository) GetPromoCodesByClinic(clinicID uint) ([]domain.PromoCode, error) {
	var codes []domain.PromoCode
	err := r.DB.Preload("Partner").Where("clinic_id = ?", clinicID).Find(&codes).Error
	return codes, err
}

func (r *MarketingRepository) UpdatePromoCode(code *domain.PromoCode) error {
	return r.DB.Save(code).Error
}

func (r *MarketingRepository) DeletePromoCode(codeID uuid.UUID, clinicID uint) error {
	return r.DB.Where("id = ? AND clinic_id = ?", codeID, clinicID).Delete(&domain.PromoCode{}).Error
}

// ReferralPartners
func (r *MarketingRepository) CreatePartner(partner *domain.ReferralPartner) error {
	return r.DB.Create(partner).Error
}

func (r *MarketingRepository) GetPartnersByClinic(clinicID uint) ([]domain.ReferralPartner, error) {
	var partners []domain.ReferralPartner
	err := r.DB.Where("clinic_id = ?", clinicID).Find(&partners).Error
	return partners, err
}

func (r *MarketingRepository) UpdatePartner(partner *domain.ReferralPartner) error {
	return r.DB.Save(partner).Error
}

func (r *MarketingRepository) DeletePartner(partnerID uuid.UUID, clinicID uint) error {
	return r.DB.Where("id = ? AND clinic_id = ?", partnerID, clinicID).Delete(&domain.ReferralPartner{}).Error
}
