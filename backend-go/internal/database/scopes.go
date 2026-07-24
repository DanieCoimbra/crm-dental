package database

import (
	"github.com/gofiber/fiber/v2"
	"gorm.io/gorm"
)

// WithTenant is a GORM scope that filters queries to ensure data isolation per clinic (tenant)
func WithTenant(target interface{}) func(db *gorm.DB) *gorm.DB {
	return func(db *gorm.DB) *gorm.DB {
		if c, ok := target.(*fiber.Ctx); ok {
			clinicIDVal := c.Locals("clinic_id")
			if clinicIDVal == nil {
				return db.Where("1 = 0")
			}
			return db.Where("clinic_id = ?", clinicIDVal)
		}
		return db.Where("clinic_id = ?", target)
	}
}
