package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"

	"github.com/gofiber/fiber/v2"
)

type TeethHandler struct {
	teethService *services.TeethService
	auditService *services.AuditService
}

func NewTeethHandler() *TeethHandler {
	return &TeethHandler{
		teethService: services.NewTeethService(),
		auditService:   services.NewAuditService(),
	}
}

type UpdateToothRequest struct {
	ToothNumber int    `json:"tooth_number"`
	Face        string `json:"face"`
	Condition   string `json:"condition"`
	Notes       string `json:"notes"`
}

func (h *TeethHandler) GetTeeth(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	patientID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	teeth, err := h.teethService.GetTeethByPatient(clinicID, uint(patientID))
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(teeth)
}

func (h *TeethHandler) UpdateTooth(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	userID := getUserID(c)

	patientID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	var req UpdateToothRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	status, err := h.teethService.UpdateToothStatus(clinicID, uint(patientID), userID, req.ToothNumber, req.Face, req.Condition, req.Notes)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, userID, "update", "teeth_status", status.ID, c.IP(), c.Get("User-Agent"), "Atualizou odontograma do paciente")

	return c.Status(fiber.StatusCreated).JSON(status)
}

func (h *TeethHandler) GetHistory(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	patientID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	history, err := h.teethService.GetTeethHistory(clinicID, uint(patientID))
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(history)
}

func (h *TeethHandler) GetByToothNumber(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	patientID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	toothNumber, err := strconv.Atoi(c.Params("tooth_number"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Número de dente inválido"})
	}

	teeth, err := h.teethService.GetTeethByToothNumber(clinicID, uint(patientID), toothNumber)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(teeth)
}
