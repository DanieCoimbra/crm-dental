package handlers

import (
	"fmt"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"dental-crm-api/internal/core/services"
	"dental-crm-api/internal/pkg/storage"

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

	fileContent, err := file.Open()
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao ler arquivo recebido"})
	}
	defer fileContent.Close()

	fileName := fmt.Sprintf("%d_%s", time.Now().Unix(), file.Filename)
	bucket := "patients_files"

	publicUrl, err := storage.UploadToSupabase(bucket, fileName, fileContent, file.Size, file.Header.Get("Content-Type"))
	if err != nil {
		return c.Status(fiber.StatusServiceUnavailable).JSON(fiber.Map{"message": "Erro de Timeout ou recusa do Supabase Storage: " + err.Error()})
	}

	fileRecord, err := h.fileService.CreateFile(clinicID, uint(patientID), file.Filename, publicUrl, file.Header.Get("Content-Type"), category)
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
