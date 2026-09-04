package handlers

import (
	"strconv"

	"dental-crm-api/internal/core/services"

	"github.com/gofiber/fiber/v2"
)

type ProcedureHandler struct {
	service *services.ProcedureService
}

func NewProcedureHandler() *ProcedureHandler {
	return &ProcedureHandler{
		service: services.NewProcedureService(),
	}
}

type CreateProcedureRequest struct {
	Name            string `json:"name"`
	Description     string `json:"description"`
	BasePriceCents  int64  `json:"base_price_cents"`
	DurationMinutes int    `json:"duration_minutes"`
	Color           string `json:"color"`
}

type UpdateProcedureRequest struct {
	Name            string `json:"name"`
	Description     string `json:"description"`
	BasePriceCents  int64  `json:"base_price_cents"`
	DurationMinutes int    `json:"duration_minutes"`
	Color           string `json:"color"`
}

func (h *ProcedureHandler) List(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	procedures, err := h.service.ListProcedures(clinicID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}
	return c.JSON(procedures)
}

func (h *ProcedureHandler) Create(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	var req CreateProcedureRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Payload inválido"})
	}

	proc, err := h.service.CreateProcedure(clinicID, req.Name, req.Description, req.BasePriceCents, req.DurationMinutes, req.Color)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(proc)
}

func (h *ProcedureHandler) Update(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	idStr := c.Params("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "ID inválido"})
	}

	var req UpdateProcedureRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Payload inválido"})
	}

	proc, err := h.service.UpdateProcedure(uint(id), clinicID, req.Name, req.Description, req.BasePriceCents, req.DurationMinutes, req.Color)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
	}

	return c.JSON(proc)
}

func (h *ProcedureHandler) Delete(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	idStr := c.Params("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "ID inválido"})
	}

	if err := h.service.DeleteProcedure(uint(id), clinicID); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
	}

	return c.JSON(fiber.Map{"message": "Procedimento removido com sucesso"})
}
