package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"
	"time"

	"github.com/gofiber/fiber/v2"
)

type ShiftAssignmentHandler struct {
	shiftService *services.ShiftAssignmentService
}

func NewShiftAssignmentHandler() *ShiftAssignmentHandler {
	return &ShiftAssignmentHandler{
		shiftService: services.NewShiftAssignmentService(),
	}
}

type CreateShiftRequest struct {
	DoctorID uint       `json:"doctor_id"`
	RoomID   uint       `json:"room_id"`
	Date     *time.Time `json:"date"`
	Shift    string     `json:"shift"`
}

func (h *ShiftAssignmentHandler) List(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	shifts, err := h.shiftService.ListShifts(clinicID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar turnos"})
	}

	return c.JSON(shifts)
}

func (h *ShiftAssignmentHandler) Create(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req CreateShiftRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	shift, err := h.shiftService.CreateShift(
		clinicID, req.DoctorID, req.RoomID, req.Date, req.Shift,
	)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(shift)
}

func (h *ShiftAssignmentHandler) Delete(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	err = h.shiftService.DeleteShift(clinicID, uint(id))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.SendStatus(fiber.StatusNoContent)
}
