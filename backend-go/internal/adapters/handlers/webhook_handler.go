package handlers

import (
	"encoding/json"
	"log"
	"os"
	"time"

	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"

	"github.com/gofiber/fiber/v2"
	"github.com/stripe/stripe-go/v78"
	"github.com/stripe/stripe-go/v78/webhook"
)

type WebhookHandler struct{}

func NewWebhookHandler() *WebhookHandler {
	return &WebhookHandler{}
}

func (h *WebhookHandler) HandleStripe(c *fiber.Ctx) error {
	endpointSecret := os.Getenv("STRIPE_WEBHOOK_SECRET")
	payload := c.Body()
	signatureHeader := c.Get("Stripe-Signature")

	event, err := webhook.ConstructEvent(payload, signatureHeader, endpointSecret)
	if err != nil {
		log.Printf("⚠️  Erro de verificação do Webhook: %v\n", err)
		return c.Status(fiber.StatusBadRequest).SendString("Invalid payload")
	}

	switch event.Type {
	case "invoice.payment_succeeded":
		var invoice stripe.Invoice
		err := json.Unmarshal(event.Data.Raw, &invoice)
		if err != nil {
			log.Printf("Erro ao parsear invoice: %v\n", err)
			return c.SendStatus(fiber.StatusBadRequest)
		}

		customerID := invoice.Customer.ID
		subscriptionID := invoice.Subscription.ID

		log.Printf("✅ Pagamento de Assinatura Sucedido! Cliente: %s, Sub: %s\n", customerID, subscriptionID)

		var sub domain.Subscription
		if err := database.DB.Where("stripe_customer_id = ? OR stripe_subscription_id = ?", customerID, subscriptionID).First(&sub).Error; err == nil {
			sub.Status = "active"
			// Add 1 month to current period
			sub.CurrentPeriodEnd = time.Now().AddDate(0, 1, 0)

			if subscriptionID != "" {
				sub.StripeSubscriptionID = subscriptionID
			}

			database.DB.Save(&sub)

			// Calculate Affiliate Commission
			if sub.CouponID != nil {
				var coupon domain.Coupon
				if err := database.DB.Preload("Affiliate").First(&coupon, *sub.CouponID).Error; err == nil && coupon.Affiliate != nil {
					affiliate := coupon.Affiliate
					amountPaidCents := invoice.AmountPaid
					commission := float64(amountPaidCents) * (affiliate.CommissionPct / 100.0)

					affiliate.Balance += commission
					database.DB.Save(affiliate)
					log.Printf("💰 Comissão de R$ %.2f adicionada ao afiliado %s", commission/100.0, affiliate.Name)
				}
			}

			var clinic domain.Clinic
			if err := database.DB.First(&clinic, sub.ClinicID).Error; err == nil {
				clinic.Status = "active"
				database.DB.Save(&clinic)
			}
		} else {
			log.Printf("Assinatura não encontrada para customer_id=%s\n", customerID)
		}

	case "invoice.payment_failed":
		var invoice stripe.Invoice
		err := json.Unmarshal(event.Data.Raw, &invoice)
		if err != nil {
			return c.SendStatus(fiber.StatusBadRequest)
		}

		customerID := invoice.Customer.ID
		var sub domain.Subscription
		if err := database.DB.Where("stripe_customer_id = ?", customerID).First(&sub).Error; err == nil {
			sub.Status = "past_due"
			database.DB.Save(&sub)
		}

	case "customer.subscription.deleted":
		var subscription stripe.Subscription
		err := json.Unmarshal(event.Data.Raw, &subscription)
		if err != nil {
			return c.SendStatus(fiber.StatusBadRequest)
		}

		var sub domain.Subscription
		if err := database.DB.Where("stripe_subscription_id = ?", subscription.ID).First(&sub).Error; err == nil {
			sub.Status = "canceled"
			database.DB.Save(&sub)
		}
	}

	return c.SendStatus(fiber.StatusOK)
}
