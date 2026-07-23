package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"
	"time"

	"github.com/gofiber/fiber/v2"
)

type AppointmentHandler struct {
	appointmentService *services.AppointmentService
	auditService       *services.AuditService
}

func NewAppointmentHandler() *AppointmentHandler {
	return &AppointmentHandler{
		appointmentService: services.NewAppointmentService(),
		auditService:       services.NewAuditService(),
	}
}

type CreateAppointmentRequest struct {
	DoctorID          uint       `json:"doctor_id"`
	PatientID         uint       `json:"patient_id"`
	RoomID            *uint      `json:"room_id"`
	AppointmentTypeID *uint      `json:"appointment_type_id"`
	StartTime         *time.Time `json:"start_time"`
	EndTime           *time.Time `json:"end_time"`
	Notes             string     `json:"notes"`
}

func (h *AppointmentHandler) List(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	start := c.Query("start")
	end := c.Query("end")

	appointments, err := h.appointmentService.ListAppointments(clinicID, start, end)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar agendamentos"})
	}

	return c.JSON(appointments)
}

func (h *AppointmentHandler) Create(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req CreateAppointmentRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	appt, err := h.appointmentService.CreateAppointment(
		clinicID, req.DoctorID, req.PatientID, req.RoomID, req.AppointmentTypeID,
		req.StartTime, req.EndTime, req.Notes,
	)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, uint(c.Locals("user_id").(float64)), "create", "appointment", appt.ID, c.IP(), c.Get("User-Agent"), "Agendamento criado")

	return c.Status(fiber.StatusCreated).JSON(appt)
}

func (h *AppointmentHandler) Delete(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	deletedBy := uint(c.Locals("user_id").(float64))
	id, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	err = h.appointmentService.DeleteAppointment(clinicID, uint(id), deletedBy)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, deletedBy, "delete", "appointment", uint(id), c.IP(), c.Get("User-Agent"), "Cancelou/Excluiu agendamento")

	return c.SendStatus(fiber.StatusNoContent)
}

func (h *AppointmentHandler) Update(c *fiber.Ctx) error {
	// Verifica a regra de negócio: Médicos não podem editar
	role, ok := c.Locals("role").(string)
	if ok && role == "doctor" {
		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"message": "Médicos não possuem permissão para editar ou remarcar consultas"})
	}

	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	var req CreateAppointmentRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	appt, err := h.appointmentService.UpdateAppointment(
		clinicID, uint(id), req.DoctorID, req.PatientID, req.RoomID, req.AppointmentTypeID,
		req.StartTime, req.EndTime, req.Notes,
	)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, uint(c.Locals("user_id").(float64)), "update", "appointment", appt.ID, c.IP(), c.Get("User-Agent"), "Atualizou agendamento")

	return c.JSON(appt)
}

func (h *AppointmentHandler) Start(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	appt, err := h.appointmentService.StartAppointment(clinicID, uint(id))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, uint(c.Locals("user_id").(float64)), "update", "appointment", appt.ID, c.IP(), c.Get("User-Agent"), "Iniciou atendimento")

	return c.JSON(appt)
}

func (h *AppointmentHandler) Finish(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	appt, err := h.appointmentService.FinishAppointment(clinicID, uint(id))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, uint(c.Locals("user_id").(float64)), "update", "appointment", appt.ID, c.IP(), c.Get("User-Agent"), "Finalizou atendimento")

	return c.JSON(appt)
}
