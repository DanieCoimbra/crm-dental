package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"

	"github.com/gofiber/fiber/v2"
)

type ClinicalEvolutionHandler struct {
	evolutionService *services.ClinicalEvolutionService
}

func NewClinicalEvolutionHandler() *ClinicalEvolutionHandler {
	return &ClinicalEvolutionHandler{
		evolutionService: services.NewClinicalEvolutionService(),
	}
}

type CreateEvolutionRequest struct {
	Content string `json:"content"`
}

func (h *ClinicalEvolutionHandler) ListByPatient(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	patientID, err := strconv.Atoi(c.Params("patient_id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	evolutions, err := h.evolutionService.ListByPatient(clinicID, uint(patientID))
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar evoluções"})
	}

	return c.JSON(evolutions)
}

func (h *ClinicalEvolutionHandler) Create(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	userID := uint(c.Locals("user_id").(float64))
	patientID, err := strconv.Atoi(c.Params("patient_id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	var req CreateEvolutionRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	evolution, err := h.evolutionService.CreateEvolution(clinicID, uint(patientID), userID, req.Content)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(evolution)
}

func (h *ClinicalEvolutionHandler) Delete(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	err = h.evolutionService.DeleteEvolution(clinicID, uint(id))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.SendStatus(fiber.StatusNoContent)
}
