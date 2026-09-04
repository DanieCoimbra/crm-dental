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
	fileService  *services.PatientFileService
	auditService *services.AuditService
}

func NewPatientFileHandler() *PatientFileHandler {
	return &PatientFileHandler{
		fileService:  services.NewPatientFileService(),
		auditService: services.NewAuditService(),
	}
}

type CreatePatientFileJSONRequest struct {
	FileName string `json:"file_name"`
	FileURL  string `json:"file_url"`
	FileType string `json:"file_type"`
	FileSize int64  `json:"file_size"`
	Category string `json:"category"`
}

func (h *PatientFileHandler) ListByPatient(c *fiber.Ctx) error {
	clinicID := getClinicID(c)

	patientIDStr := c.Params("patient_id")
	if patientIDStr == "" {
		patientIDStr = c.Params("id")
	}

	patientID, err := strconv.Atoi(patientIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	files, err := h.fileService.ListByPatient(clinicID, uint(patientID))
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(files)
}

func (h *PatientFileHandler) Create(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	uploadedBy := getUserID(c)

	patientIDStr := c.Params("patient_id")
	if patientIDStr == "" {
		patientIDStr = c.Params("id")
	}

	patientID, err := strconv.Atoi(patientIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	// First try JSON body if available
	var jsonReq CreatePatientFileJSONRequest
	if err := c.BodyParser(&jsonReq); err == nil && jsonReq.FileURL != "" {
		fileName := jsonReq.FileName
		if fileName == "" {
			fileName = "anexo"
		}
		fileRecord, err := h.fileService.CreateFileRecord(clinicID, uint(patientID), uploadedBy, fileName, jsonReq.FileURL, jsonReq.FileType, jsonReq.Category, jsonReq.FileSize)
		if err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
		}
		h.auditService.LogAction(clinicID, uploadedBy, "create", "patient_file", fileRecord.ID, c.IP(), c.Get("User-Agent"), "Anexou arquivo ao prontuário")
		return c.Status(fiber.StatusCreated).JSON(fileRecord)
	}

	// Otherwise process multipart form
	category := c.FormValue("category", "Geral")
	fileTypeStr := c.FormValue("file_type", c.FormValue("type", "OUTRO"))

	file, err := c.FormFile("file")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Arquivo não enviado"})
	}

	ext := strings.ToLower(filepath.Ext(file.Filename))
	if ext != ".pdf" && ext != ".jpg" && ext != ".jpeg" && ext != ".png" && ext != ".dicom" && ext != ".dcm" {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Formato de arquivo não suportado. Envie PDF, JPG, PNG ou DICOM."})
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

	fileRecord, err := h.fileService.CreateFileRecord(clinicID, uint(patientID), uploadedBy, file.Filename, publicUrl, fileTypeStr, category, file.Size)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, uploadedBy, "create", "patient_file", fileRecord.ID, c.IP(), c.Get("User-Agent"), "Anexou arquivo ao prontuário")

	return c.Status(fiber.StatusCreated).JSON(fileRecord)
}

func (h *PatientFileHandler) Delete(c *fiber.Ctx) error {
	clinicID := getClinicID(c)
	userID := getUserID(c)

	fileIDStr := c.Params("file_id")
	if fileIDStr == "" {
		fileIDStr = c.Params("id")
	}

	id, err := strconv.Atoi(fileIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de arquivo inválido"})
	}

	err = h.fileService.DeleteFile(clinicID, uint(id))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, userID, "delete", "patient_file", uint(id), c.IP(), c.Get("User-Agent"), "Excluiu arquivo do paciente")

	return c.SendStatus(fiber.StatusNoContent)
}
