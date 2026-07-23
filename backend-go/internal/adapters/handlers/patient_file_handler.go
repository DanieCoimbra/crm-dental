package handlers

import (
	"dental-crm-api/internal/core/services"
	"fmt"
	"net/http"
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

	fileContent, err := file.Open()
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao ler arquivo recebido"})
	}
	defer fileContent.Close()

	fileName := fmt.Sprintf("%d_%s", time.Now().Unix(), file.Filename)
	supabaseUrl := os.Getenv("SUPABASE_URL")
	supabaseKey := os.Getenv("SUPABASE_KEY")
	bucket := "patients_files" // O bucket configurado no supabase

	uploadUrl := fmt.Sprintf("%s/storage/v1/object/%s/%s", supabaseUrl, bucket, fileName)

	// Stream file directly using fileContent (io.Reader) - sem usar AWS SDK
	reqSupabase, err := http.NewRequest("POST", uploadUrl, fileContent)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao construir requisição para Supabase"})
	}

	reqSupabase.ContentLength = file.Size // EVITA ENVIO CHUNKED QUE CAUSA FALHAS

	reqSupabase.Header.Set("Authorization", "Bearer "+supabaseKey)
	reqSupabase.Header.Set("Content-Type", file.Header.Get("Content-Type"))

	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Do(reqSupabase)
	if err != nil || resp.StatusCode >= 400 {
		return c.Status(fiber.StatusServiceUnavailable).JSON(fiber.Map{"message": "Erro de Timeout ou recusa do Supabase Storage"})
	}
	defer resp.Body.Close()

	publicUrl := fmt.Sprintf("%s/storage/v1/object/public/%s/%s", supabaseUrl, bucket, fileName)

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
