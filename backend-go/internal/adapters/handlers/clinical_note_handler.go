package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"
	"time"

	"github.com/gofiber/fiber/v2"
)

type ClinicalNoteHandler struct {
	noteService  *services.ClinicalNoteService
	auditService *services.AuditService
}

func NewClinicalNoteHandler() *ClinicalNoteHandler {
	return &ClinicalNoteHandler{
		noteService:  services.NewClinicalNoteService(),
		auditService: services.NewAuditService(),
	}
}

type CreateClinicalNoteRequest struct {
	AttendanceDate   *time.Time `json:"attendance_date"`
	ChiefComplaint   string     `json:"chief_complaint"`
	Diagnosis        string     `json:"diagnosis"`
	ProcedureSummary string     `json:"procedure_summary"`
}

func (h *ClinicalNoteHandler) ListByPatient(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	patientID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	notes, err := h.noteService.ListNotesByPatient(clinicID, uint(patientID))
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(notes)
}

func (h *ClinicalNoteHandler) Create(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	dentistID := getUserID(c)

	patientID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	var req CreateClinicalNoteRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	var attDate time.Time
	if req.AttendanceDate != nil {
		attDate = *req.AttendanceDate
	} else {
		attDate = time.Now()
	}

	note, err := h.noteService.CreateNote(clinicID, uint(patientID), dentistID, attDate, req.ChiefComplaint, req.Diagnosis, req.ProcedureSummary)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, dentistID, "create", "clinical_note", note.ID, c.IP(), c.Get("User-Agent"), "Criou nova anotação clínica")

	return c.Status(fiber.StatusCreated).JSON(note)
}
