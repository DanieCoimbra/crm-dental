package handlers

import (
	"fmt"
	"strings"
	"time"

	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"dental-crm-api/internal/pkg/utils"

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
	clinicID, ok := utils.GetClinicID(c)
	if !ok {
		return c.Status(401).JSON(fiber.Map{"error": "Clínica não identificada"})
	}

	var req struct {
		CouponCode string `json:"coupon_code"`
		Code       string `json:"code"`
	}

	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	rawCode := req.CouponCode
	if rawCode == "" {
		rawCode = req.Code
	}

	code := strings.ToUpper(strings.TrimSpace(rawCode))

	tx := database.DB.Begin()

	var coupon domain.Coupon
	if err := tx.Where("code = ?", code).First(&coupon).Error; err != nil {
		tx.Rollback()
		return c.Status(400).JSON(fiber.Map{"error": "Cupom inválido ou expirado"})
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

	discountPercent := int(coupon.DiscountValue)
	return c.JSON(fiber.Map{
		"message":          "Cupom aplicado com sucesso",
		"discount_percent": discountPercent,
	})
}

func (h *SaaSHandler) ChangePlan(c *fiber.Ctx) error {
	clinicID, ok := utils.GetClinicID(c)
	if !ok {
		return c.Status(401).JSON(fiber.Map{"error": "Clínica não identificada"})
	}

	var req struct {
		Plan         string `json:"plan"`
		NewPlan      string `json:"new_plan"`
		BillingCycle string `json:"billing_cycle"`
	}

	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	planName := strings.ToLower(req.NewPlan)
	if planName == "" {
		planName = strings.ToLower(req.Plan)
	}

	if planName != "start" && planName != "basic" && planName != "pro" && planName != "enterprise" && planName != "premium" {
		return c.Status(400).JSON(fiber.Map{"error": "Plano inválido"})
	}

	var clinic domain.Clinic
	if err := database.DB.First(&clinic, clinicID).Error; err != nil {
		return c.Status(404).JSON(fiber.Map{"error": "Clínica não encontrada"})
	}

	clinic.Status = "active"
	clinic.Plan = planName
	if err := database.DB.Save(&clinic).Error; err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao atualizar plano"})
	}

	// Atualiza ou cria a subscription associada
	var subscription domain.Subscription
	if err := database.DB.Where("clinic_id = ?", clinicID).First(&subscription).Error; err == nil {
		subscription.Status = "active"
		subscription.Plan = planName
		database.DB.Save(&subscription)
	}

	return c.JSON(fiber.Map{"message": "Plano alterado com sucesso", "status": clinic.Status, "plan": clinic.Plan})
}

func (h *SaaSHandler) CreateCheckoutSession(c *fiber.Ctx) error {
	var req struct {
		PlanTier     string `json:"plan_tier"`
		BillingCycle string `json:"billing_cycle"`
		CouponCode   string `json:"coupon_code,omitempty"`
	}

	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	if req.PlanTier == "" {
		req.PlanTier = "pro"
	}
	if req.BillingCycle == "" {
		req.BillingCycle = "monthly"
	}

	sessionID := fmt.Sprintf("cs_simulated_%d", time.Now().UnixNano())
	checkoutURL := fmt.Sprintf("/register-clinic?session_id=%s&plan=%s&billing=%s", sessionID, req.PlanTier, req.BillingCycle)

	return c.JSON(fiber.Map{
		"checkout_url":  checkoutURL,
		"session_id":    sessionID,
		"plan_tier":     req.PlanTier,
		"billing_cycle": req.BillingCycle,
	})
}

func (h *SaaSHandler) ValidateCheckoutSession(c *fiber.Ctx) error {
	sessionID := c.Query("session_id")
	if sessionID == "" {
		return c.Status(400).JSON(fiber.Map{"error": "session_id é obrigatório"})
	}

	var used domain.UsedCheckoutSession
	if err := database.DB.Where("session_id = ?", sessionID).First(&used).Error; err == nil {
		return c.Status(400).JSON(fiber.Map{"error": "Sessão de pagamento já utilizada"})
	}

	return c.JSON(fiber.Map{
		"valid":      true,
		"session_id": sessionID,
		"plan_tier":  "pro",
		"email":      "",
	})
}
