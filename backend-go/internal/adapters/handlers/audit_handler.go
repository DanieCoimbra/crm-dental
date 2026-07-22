package handlers

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"

	"github.com/gofiber/fiber/v2"
)

type AuditHandler struct{}

func NewAuditHandler() *AuditHandler {
	return &AuditHandler{}
}

func (h *AuditHandler) List(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var logs []domain.AuditLog
	database.DB.Preload("User").Where("clinic_id = ?", clinicID).Order("created_at desc").Limit(50).Find(&logs)

	return c.JSON(logs)
}
