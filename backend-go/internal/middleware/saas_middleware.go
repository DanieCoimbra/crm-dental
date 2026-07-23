package middleware

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"time"

	"github.com/gofiber/fiber/v2"
)

func RequireActiveSubscription() fiber.Handler {
	return func(c *fiber.Ctx) error {
		if c.Method() == fiber.MethodGet || c.Method() == fiber.MethodOptions {
			return c.Next()
		}

		// Pega o ID da clínica já decodificado pelo auth_middleware
		clinicIDVal := c.Locals("clinic_id")
		if clinicIDVal == nil {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"message": "Não autorizado"})
		}

		clinicID := uint(clinicIDVal.(float64))

		// Busca a assinatura da clínica
		var subscription domain.Subscription
		if err := database.DB.Where("clinic_id = ?", clinicID).First(&subscription).Error; err != nil {
			// Se não tem assinatura, cria uma de trial de 14 dias (Fall-back)
			trialEnds := time.Now().Add(14 * 24 * time.Hour)
			subscription = domain.Subscription{
				ClinicID:    clinicID,
				Status:      "trialing",
				Plan:        "monthly",
				TrialEndsAt: trialEnds,
			}
			database.DB.Create(&subscription)
		}

		// Checa validade do Trial
		if subscription.Status == "trialing" {
			if time.Now().After(subscription.TrialEndsAt) {
				return c.Status(fiber.StatusPaymentRequired).JSON(fiber.Map{
					"error":   "TRIAL_EXPIRED",
					"message": "Seu período de testes expirou. Por favor, assine um plano para continuar usando o sistema.",
				})
			}
			return c.Next() // Trial válido
		}

		// Checa validade de Active (Assinatura paga)
		if subscription.Status == "active" {
			// Dá uma margem de segurança de 3 dias de grace period
			gracePeriod := subscription.CurrentPeriodEnd.Add(3 * 24 * time.Hour)
			if time.Now().After(gracePeriod) {
				return c.Status(fiber.StatusPaymentRequired).JSON(fiber.Map{
					"error":   "SUBSCRIPTION_EXPIRED",
					"message": "Sua assinatura expirou e o pagamento não foi identificado. Por favor, regularize sua conta.",
				})
			}
			return c.Next() // Ativo
		}

		// Qualquer outro status (past_due, canceled, etc)
		return c.Status(fiber.StatusPaymentRequired).JSON(fiber.Map{
			"error":   "SUBSCRIPTION_PAST_DUE",
			"message": "Identificamos um problema com seu pagamento. Acesse o painel financeiro para regularizar.",
		})
	}
}
