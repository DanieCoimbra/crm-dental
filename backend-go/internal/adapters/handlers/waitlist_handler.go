package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"
	"time"

	"github.com/gofiber/fiber/v2"
)

type WaitlistHandler struct {
	waitlistService *services.WaitlistService
}

func NewWaitlistHandler() *WaitlistHandler {
	return &WaitlistHandler{
		waitlistService: services.NewWaitlistService(),
	}
}

type CreateWaitlistRequest struct {
	PatientID          uint   `json:"patient_id"`
	DoctorID           *uint  `json:"doctor_id"`
	AppointmentTypeID  *uint  `json:"appointment_type_id"`
	PreferredDays      string `json:"preferred_days"`
	PreferredTimeRange string `json:"preferred_time_range"`
	UrgencyLevel       string `json:"urgency_level"`
	Notes              string `json:"notes"`
}

func (h *WaitlistHandler) List(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	entries, err := h.waitlistService.ListEntries(clinicID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar fila de espera"})
	}

	return c.JSON(entries)
}

func (h *WaitlistHandler) Create(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req CreateWaitlistRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	entry, err := h.waitlistService.CreateEntry(
		clinicID, req.PatientID, req.DoctorID, req.AppointmentTypeID,
		req.PreferredDays, req.PreferredTimeRange, req.UrgencyLevel, req.Notes,
	)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(entry)
}

func (h *WaitlistHandler) Delete(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	id, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	err = h.waitlistService.DeleteEntry(clinicID, uint(id))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.SendStatus(fiber.StatusNoContent)
}

func (h *WaitlistHandler) Update(c *fiber.Ctx) error {
	// Regra de negócio: Médicos não gerenciam a fila de espera
	role, ok := c.Locals("role").(string)
	if ok && role == "doctor" {
		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"message": "Médicos não possuem permissão para gerenciar a fila de espera"})
	}

	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	// Aproveitando o mesmo DTO e adicionando um status temporário no parsing
	type UpdateWaitlistRequest struct {
		DoctorID           *uint  `json:"doctor_id"`
		AppointmentTypeID  *uint  `json:"appointment_type_id"`
		PreferredDays      string `json:"preferred_days"`
		PreferredTimeRange string `json:"preferred_time_range"`
		UrgencyLevel       string `json:"urgency_level"`
		Notes              string `json:"notes"`
		Status             string `json:"status"`
	}

	var req UpdateWaitlistRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	entry, err := h.waitlistService.UpdateEntry(
		clinicID, uint(id), req.DoctorID, req.AppointmentTypeID,
		req.PreferredDays, req.PreferredTimeRange, req.UrgencyLevel, req.Notes, req.Status,
	)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(entry)
}

type CheckMatchesRequest struct {
	DoctorID  uint   `json:"doctor_id"`
	StartTime string `json:"start_time"`
}

func (h *WaitlistHandler) CheckMatches(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req CheckMatchesRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	startTime, err := time.Parse(time.RFC3339, req.StartTime)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Data de início inválida. Use formato RFC3339 (ex: 2026-07-16T10:00:00Z)"})
	}

	matches, err := h.waitlistService.CheckMatches(clinicID, req.DoctorID, startTime)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(matches)
}

func (h *WaitlistHandler) GetByID(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	entry, err := h.waitlistService.GetEntryByID(clinicID, uint(id))
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(entry)
}
