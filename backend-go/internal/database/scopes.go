package database

import (
	"gorm.io/gorm"
)

// WithTenant is a GORM scope that filters queries to ensure data isolation per clinic (tenant)
func WithTenant(clinicID uint) func(db *gorm.DB) *gorm.DB {
	return func(db *gorm.DB) *gorm.DB {
		return db.Where("clinic_id = ?", clinicID)
	}
}
