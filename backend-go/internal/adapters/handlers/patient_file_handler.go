package handlers

import (
	"dental-crm-api/internal/core/services"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gofiber/fiber/v2"
)

type PatientFileHandler struct {
	fileService *services.PatientFileService
}

func NewPatientFileHandler() *PatientFileHandler {
	return &PatientFileHandler{
		fileService: services.NewPatientFileService(),
	}
}

func (h *PatientFileHandler) ListByPatient(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	patientID, err := strconv.Atoi(c.Params("patient_id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	files, err := h.fileService.ListByPatient(clinicID, uint(patientID))
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar arquivos"})
	}

	return c.JSON(files)
}

func (h *PatientFileHandler) Create(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	patientID, err := strconv.Atoi(c.Params("patient_id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	category := c.FormValue("category", "Geral")

	file, err := c.FormFile("file")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Arquivo não enviado"})
	}

	ext := strings.ToLower(filepath.Ext(file.Filename))
	if ext != ".pdf" && ext != ".jpg" && ext != ".jpeg" && ext != ".png" {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Formato de arquivo não suportado. Envie PDF, JPG ou PNG."})
	}

	// Create uploads dir if not exists
	os.MkdirAll("./uploads", os.ModePerm)
	
	fileName := fmt.Sprintf("%d_%s", time.Now().Unix(), file.Filename)
	filePath := fmt.Sprintf("./uploads/%s", fileName)
	
	if err := c.SaveFile(file, filePath); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao salvar arquivo"})
	}

	fileRecord, err := h.fileService.CreateFile(clinicID, uint(patientID), file.Filename, filePath, file.Header.Get("Content-Type"), category)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(fileRecord)
}

func (h *PatientFileHandler) Delete(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	err = h.fileService.DeleteFile(clinicID, uint(id))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.SendStatus(fiber.StatusNoContent)
}
