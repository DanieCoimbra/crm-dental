package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"

	"github.com/gofiber/fiber/v2"
)

type MedicalDocumentHandler struct {
	docService   *services.MedicalDocumentService
	auditService *services.AuditService
}

func NewMedicalDocumentHandler() *MedicalDocumentHandler {
	return &MedicalDocumentHandler{
		docService:   services.NewMedicalDocumentService(),
		auditService: services.NewAuditService(),
	}
}

type CreateMedicalDocumentRequest struct {
	Type    string `json:"type"`
	Title   string `json:"title"`
	Content string `json:"content"`
}

func (h *MedicalDocumentHandler) ListByPatient(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	patientID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	docs, err := h.docService.ListDocumentsByPatient(clinicID, uint(patientID))
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(docs)
}

func (h *MedicalDocumentHandler) Create(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	dentistID := getUserID(c)

	patientID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	var req CreateMedicalDocumentRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	doc, err := h.docService.CreateDocument(clinicID, uint(patientID), dentistID, req.Type, req.Title, req.Content)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, dentistID, "create", "medical_document", doc.ID, c.IP(), c.Get("User-Agent"), "Criou documento médico: "+doc.Title)

	return c.Status(fiber.StatusCreated).JSON(doc)
}

func (h *MedicalDocumentHandler) GetByID(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	docID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de documento inválido"})
	}

	doc, err := h.docService.GetDocumentByID(uint(docID), clinicID)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(doc)
}

func (h *MedicalDocumentHandler) Delete(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	dentistID := getUserID(c)

	docID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de documento inválido"})
	}

	err = h.docService.DeleteDocument(uint(docID), clinicID)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, dentistID, "delete", "medical_document", uint(docID), c.IP(), c.Get("User-Agent"), "Excluiu documento médico")

	return c.JSON(fiber.Map{"message": "Documento excluído com sucesso"})
}
