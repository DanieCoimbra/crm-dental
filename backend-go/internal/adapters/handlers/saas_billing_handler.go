package handlers

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"strings"
	"time"

	"github.com/gofiber/fiber/v2"
)

type SaaSHandler struct{}

func NewSaaSHandler() *SaaSHandler {
	return &SaaSHandler{}
}

// Admin only
func (h *SaaSHandler) ListCoupons(c *fiber.Ctx) error {
	var coupons []domain.Coupon
	if err := database.DB.Preload("Affiliate").Find(&coupons).Error; err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao listar cupons"})
	}
	return c.JSON(coupons)
}

func (h *SaaSHandler) CreateCoupon(c *fiber.Ctx) error {
	var req domain.Coupon
	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	req.Code = strings.ToUpper(req.Code)

	if err := database.DB.Create(&req).Error; err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao criar cupom"})
	}

	return c.Status(201).JSON(req)
}

// Clinic usage
func (h *SaaSHandler) ValidateCoupon(c *fiber.Ctx) error {
	var req struct {
		Code string `json:"code"`
	}

	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	code := strings.ToUpper(req.Code)

	var coupon domain.Coupon
	if err := database.DB.Preload("Affiliate").Where("code = ?", code).First(&coupon).Error; err != nil {
		return c.Status(404).JSON(fiber.Map{"error": "Cupom inválido ou não encontrado"})
	}

	if coupon.ExpiresAt != nil && coupon.ExpiresAt.Before(time.Now()) {
		return c.Status(400).JSON(fiber.Map{"error": "Este cupom já expirou"})
	}

	if coupon.MaxUses > 0 && coupon.UsesCount >= coupon.MaxUses {
		return c.Status(400).JSON(fiber.Map{"error": "Limite de uso do cupom atingido"})
	}

	return c.JSON(coupon)
}

func (h *SaaSHandler) ApplyCoupon(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req struct {
		Code string `json:"code"`
	}

	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	code := strings.ToUpper(req.Code)

	tx := database.DB.Begin()

	var coupon domain.Coupon
	if err := tx.Where("code = ?", code).First(&coupon).Error; err != nil {
		tx.Rollback()
		return c.Status(404).JSON(fiber.Map{"error": "Cupom inválido ou não encontrado"})
	}

	if coupon.ExpiresAt != nil && coupon.ExpiresAt.Before(time.Now()) {
		tx.Rollback()
		return c.Status(400).JSON(fiber.Map{"error": "Este cupom já expirou"})
	}

	if coupon.MaxUses > 0 && coupon.UsesCount >= coupon.MaxUses {
		tx.Rollback()
		return c.Status(400).JSON(fiber.Map{"error": "Limite de uso do cupom atingido"})
	}

	// Update clinic subscription
	coupon.UsesCount += 1
	if err := tx.Save(&coupon).Error; err != nil {
		tx.Rollback()
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao atualizar uso do cupom"})
	}

	var subscription domain.Subscription
	if err := tx.Where("clinic_id = ?", clinicID).First(&subscription).Error; err == nil {
		subscription.CouponID = &coupon.ID
		if err := tx.Save(&subscription).Error; err != nil {
			tx.Rollback()
			return c.Status(500).JSON(fiber.Map{"error": "Erro ao atrelar cupom à assinatura"})
		}
	} else {
		// Se não existe subscription, cria uma básica (pode estar em trial)
		subscription = domain.Subscription{
			ClinicID: clinicID,
			CouponID: &coupon.ID,
			Status:   "trialing",
			Plan:     "basic",
		}
		if err := tx.Create(&subscription).Error; err != nil {
			tx.Rollback()
			return c.Status(500).JSON(fiber.Map{"error": "Erro ao criar assinatura com cupom"})
		}
	}

	tx.Commit()

	return c.JSON(fiber.Map{
		"message":   "Cupom aplicado com sucesso",
		"clinic_id": clinicID,
	})
}

func (h *SaaSHandler) ChangePlan(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req struct {
		Plan string `json:"plan"`
	}

	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	if req.Plan != "basic" && req.Plan != "pro" && req.Plan != "premium" {
		return c.Status(400).JSON(fiber.Map{"error": "Plano inválido"})
	}

	var clinic domain.Clinic
	if err := database.DB.First(&clinic, clinicID).Error; err != nil {
		return c.Status(404).JSON(fiber.Map{"error": "Clínica não encontrada"})
	}

	// Always active since they chose a plan (in a real scenario, this happens after payment)
	clinic.Status = "active"
	clinic.Plan = req.Plan
	if err := database.DB.Save(&clinic).Error; err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao atualizar plano"})
	}

	return c.JSON(fiber.Map{"message": "Plano atualizado com sucesso", "status": clinic.Status, "plan": clinic.Plan})
}
