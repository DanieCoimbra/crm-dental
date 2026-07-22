package handlers

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
)

type MarketingHandler struct {
	Repo *repositories.MarketingRepository
}

func NewMarketingHandler(repo *repositories.MarketingRepository) *MarketingHandler {
	return &MarketingHandler{Repo: repo}
}

func (h *MarketingHandler) GetPromoCodes(c *fiber.Ctx) error {
	clinicIDStr := c.Locals("clinic_id").(string)
	clinicID, _ := uuid.Parse(clinicIDStr)

	codes, err := h.Repo.GetPromoCodesByClinic(clinicID)
	if err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao buscar códigos promocionais"})
	}
	return c.JSON(codes)
}

func (h *MarketingHandler) CreatePromoCode(c *fiber.Ctx) error {
	clinicIDStr := c.Locals("clinic_id").(string)
	clinicID, _ := uuid.Parse(clinicIDStr)

	var req domain.PromoCode
	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	req.ClinicID = clinicID
	if err := h.Repo.CreatePromoCode(&req); err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao criar código promocional"})
	}

	return c.Status(201).JSON(req)
}

func (h *MarketingHandler) DeletePromoCode(c *fiber.Ctx) error {
	clinicIDStr := c.Locals("clinic_id").(string)
	clinicID, _ := uuid.Parse(clinicIDStr)

	codeIDStr := c.Params("id")
	codeID, err := uuid.Parse(codeIDStr)
	if err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "ID inválido"})
	}

	if err := h.Repo.DeletePromoCode(codeID, clinicID); err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao deletar código promocional"})
	}

	return c.JSON(fiber.Map{"message": "Código deletado com sucesso"})
}

func (h *MarketingHandler) GetPartners(c *fiber.Ctx) error {
	clinicIDStr := c.Locals("clinic_id").(string)
	clinicID, _ := uuid.Parse(clinicIDStr)

	partners, err := h.Repo.GetPartnersByClinic(clinicID)
	if err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao buscar afiliados/parceiros"})
	}
	return c.JSON(partners)
}

func (h *MarketingHandler) CreatePartner(c *fiber.Ctx) error {
	clinicIDStr := c.Locals("clinic_id").(string)
	clinicID, _ := uuid.Parse(clinicIDStr)

	var req domain.ReferralPartner
	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	req.ClinicID = clinicID
	if err := h.Repo.CreatePartner(&req); err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao criar parceiro"})
	}

	return c.Status(201).JSON(req)
}

func (h *MarketingHandler) DeletePartner(c *fiber.Ctx) error {
	clinicIDStr := c.Locals("clinic_id").(string)
	clinicID, _ := uuid.Parse(clinicIDStr)

	partnerIDStr := c.Params("id")
	partnerID, err := uuid.Parse(partnerIDStr)
	if err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "ID inválido"})
	}

	if err := h.Repo.DeletePartner(partnerID, clinicID); err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao deletar parceiro"})
	}

	return c.JSON(fiber.Map{"message": "Parceiro deletado com sucesso"})
}
