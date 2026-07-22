package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"

	"github.com/gofiber/fiber/v2"
)

type ClinicHandler struct {
	clinicService *services.ClinicService
}

func NewClinicHandler() *ClinicHandler {
	return &ClinicHandler{
		clinicService: services.NewClinicService(),
	}
}

func (h *ClinicHandler) GetMe(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	clinic, err := h.clinicService.GetClinic(clinicID)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(clinic)
}

type UpdateClinicRequest struct {
	Name  string `json:"name"`
	CNPJ  string `json:"cnpj"`
	Email string `json:"email"`
	Phone string `json:"phone"`
}

func (h *ClinicHandler) Update(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	// Ensure the user is updating their own clinic
	if clinicID != uint(id) {
		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"message": "Acesso negado: você só pode editar a sua própria clínica"})
	}

	var req UpdateClinicRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	clinic, err := h.clinicService.UpdateClinic(clinicID, req.Name, req.CNPJ, req.Email, req.Phone)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(clinic)
}
