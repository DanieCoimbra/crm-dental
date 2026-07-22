package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"

	"github.com/gofiber/fiber/v2"
)

type AppointmentTypeHandler struct {
	typeService *services.AppointmentTypeService
}

func NewAppointmentTypeHandler() *AppointmentTypeHandler {
	return &AppointmentTypeHandler{
		typeService: services.NewAppointmentTypeService(),
	}
}

func (h *AppointmentTypeHandler) List(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	types, err := h.typeService.ListTypes(clinicID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar tipos de agendamento"})
	}

	return c.JSON(types)
}

type CreateAppointmentTypeRequest struct {
	Name            string `json:"name"`
	Description     string `json:"description"`
	DurationMinutes int    `json:"duration_minutes"`
	Color           string `json:"color"`
}

func (h *AppointmentTypeHandler) Create(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req CreateAppointmentTypeRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	// Default duration is 30 minutes if not provided or zero
	if req.DurationMinutes <= 0 {
		req.DurationMinutes = 30
	}

	apptType, err := h.typeService.CreateType(clinicID, req.Name, req.Description, req.DurationMinutes, req.Color)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(apptType)
}

func (h *AppointmentTypeHandler) Delete(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	deletedBy := uint(c.Locals("user_id").(float64))
	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	if err := h.typeService.DeleteType(clinicID, uint(id), deletedBy); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.SendStatus(fiber.StatusNoContent)
}

func (h *AppointmentTypeHandler) Update(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	
	typeID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	var req CreateAppointmentTypeRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	apptType, err := h.typeService.UpdateType(clinicID, uint(typeID), req.Name, req.Description, req.DurationMinutes, req.Color)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(apptType)
}

func (h *AppointmentTypeHandler) GetByID(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	
	typeID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	apptType, err := h.typeService.GetType(clinicID, uint(typeID))
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(apptType)
}
