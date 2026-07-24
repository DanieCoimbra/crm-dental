package middleware

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"dental-crm-api/internal/pkg/utils"
	"time"

	"github.com/gofiber/fiber/v2"
)

func RequireActiveSubscription() fiber.Handler {
	return func(c *fiber.Ctx) error {
		// Permitir OPTIONS
		if c.Method() == fiber.MethodOptions {
			return c.Next()
		}

		clinicID, ok := utils.GetClinicID(c)
		if !ok {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Não autorizado"})
		}

		var clinic domain.Clinic
		if err := database.DB.First(&clinic, clinicID).Error; err != nil {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não encontrada"})
		}

		// Busca a assinatura da clínica
		var subscription domain.Subscription
		if err := database.DB.Where("clinic_id = ?", clinicID).First(&subscription).Error; err != nil {
			// Se não tem assinatura, cria uma de trial de 14 dias (Fallback)
			trialEnds := time.Now().Add(14 * 24 * time.Hour)
			subscription = domain.Subscription{
				ClinicID:    clinicID,
				Status:      "trialing",
				Plan:        "monthly",
				TrialEndsAt: trialEnds,
			}
			database.DB.Create(&subscription)
		}

		status := clinic.Status
		if status == "" {
			status = subscription.Status
		}

		// Checa validade do Trial
		if status == "trial" || status == "trialing" || subscription.Status == "trialing" {
			isSubExpired := !subscription.TrialEndsAt.IsZero() && subscription.TrialEndsAt.Before(time.Now())
			isClinicExpired := clinic.TrialEndsAt != nil && clinic.TrialEndsAt.Before(time.Now())
			if isSubExpired || isClinicExpired {
				return c.Status(fiber.StatusPaymentRequired).JSON(fiber.Map{
					"error": "Assinatura expirada. Por favor regularize seu plano nas configurações.",
				})
			}
			return c.Next()
		}

		// Checa Grace Period (3 dias de tolerância)
		if status == "grace_period" || status == "past_due" || subscription.Status == "past_due" || subscription.Status == "grace_period" {
			if subscription.GracePeriodEndsAt != nil && time.Now().After(*subscription.GracePeriodEndsAt) {
				return c.Status(fiber.StatusPaymentRequired).JSON(fiber.Map{
					"error": "Assinatura expirada. Por favor regularize seu plano nas configurações.",
				})
			}
			c.Set("X-Grace-Period", "true")
			return c.Next()
		}

		// Checa validade de Active (Assinatura paga)
		if status == "active" || subscription.Status == "active" {
			return c.Next()
		}

		// Qualquer outro status (canceled, etc)
		return c.Status(fiber.StatusPaymentRequired).JSON(fiber.Map{
			"error": "Assinatura expirada. Por favor regularize seu plano nas configurações.",
		})
	}
}
