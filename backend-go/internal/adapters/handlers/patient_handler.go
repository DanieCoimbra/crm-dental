package handlers

import (
	"dental-crm-api/internal/core/services"
	"encoding/csv"
	"fmt"
	"strconv"
	"strings"

	"github.com/gofiber/fiber/v2"
)

type PatientHandler struct {
	patientService *services.PatientService
	auditService   *services.AuditService
}

func NewPatientHandler() *PatientHandler {
	return &PatientHandler{
		patientService: services.NewPatientService(),
		auditService:   services.NewAuditService(),
	}
}

type CreatePatientRequest struct {
	Name            string `json:"name"`
	CPF             string `json:"cpf"`
	Email           string `json:"email"`
	Phone           string `json:"phone"`
	Cep             string `json:"cep"`
	Street          string `json:"street"`
	Neighborhood    string `json:"neighborhood"`
	Number          string `json:"number"`
	HealthInsurance string `json:"health_insurance"`
}

func (h *PatientHandler) List(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	search := c.Query("search")
	page := c.QueryInt("page", 1)

	patients, err := h.patientService.ListPatients(clinicID, search, page)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar pacientes"})
	}

	return c.JSON(patients)
}

func (h *PatientHandler) Create(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req CreatePatientRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	patient, err := h.patientService.CreatePatient(clinicID, req.Name, req.CPF, req.Email, req.Phone, req.Cep, req.Street, req.Neighborhood, req.Number, req.HealthInsurance)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, uint(c.Locals("user_id").(float64)), "create", "patient", patient.ID, c.IP(), c.Get("User-Agent"), "Criou novo paciente")

	return c.Status(fiber.StatusCreated).JSON(patient)
}

func (h *PatientHandler) Delete(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	deletedBy := uint(c.Locals("user_id").(float64))
	
	patientID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	err = h.patientService.DeletePatient(clinicID, uint(patientID), deletedBy)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, deletedBy, "delete", "patient", uint(patientID), c.IP(), c.Get("User-Agent"), "Excluiu paciente (enviado para lixeira)")

	return c.SendStatus(fiber.StatusNoContent)
}

func (h *PatientHandler) Update(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	var req CreatePatientRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	patient, err := h.patientService.UpdatePatient(clinicID, uint(id), req.Name, req.CPF, req.Email, req.Phone, req.Cep, req.Street, req.Neighborhood, req.Number, req.HealthInsurance)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, uint(c.Locals("user_id").(float64)), "update", "patient", patient.ID, c.IP(), c.Get("User-Agent"), "Atualizou dados do paciente")

	return c.JSON(patient)
}

type UpdateEMRRequest struct {
	MedicalHistory string `json:"medical_history"`
	Notes          string `json:"notes"`
}

func (h *PatientHandler) UpdateEMR(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	var req UpdateEMRRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	patient, err := h.patientService.UpdateEMR(clinicID, uint(id), req.MedicalHistory, req.Notes)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	h.auditService.LogAction(clinicID, uint(c.Locals("user_id").(float64)), "update", "patient", patient.ID, c.IP(), c.Get("User-Agent"), "Atualizou anamnese / prontuário do paciente")

	return c.JSON(patient)
}

func (h *PatientHandler) Import(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	file, err := c.FormFile("file")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Nenhum arquivo enviado"})
	}

	f, err := file.Open()
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao abrir arquivo"})
	}
	defer f.Close()

	reader := csv.NewReader(f)
	records, err := reader.ReadAll()
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Erro ao ler arquivo CSV"})
	}

	successCount := 0
	for i, row := range records {
		if i == 0 && (strings.ToLower(row[0]) == "nome" || strings.ToLower(row[0]) == "name") {
			continue
		}

		for len(row) < 9 {
			row = append(row, "")
		}

		name := strings.TrimSpace(row[0])
		cpf := strings.TrimSpace(row[1])
		email := strings.TrimSpace(row[2])
		phone := strings.TrimSpace(row[3])
		cep := strings.TrimSpace(row[4])
		street := strings.TrimSpace(row[5])
		neighborhood := strings.TrimSpace(row[6])
		number := strings.TrimSpace(row[7])
		healthInsurance := strings.TrimSpace(row[8])

		if name == "" {
			continue
		}

		_, err := h.patientService.CreatePatient(clinicID, name, cpf, email, phone, cep, street, neighborhood, number, healthInsurance)
		if err == nil {
			successCount++
		}
	}

	return c.JSON(fiber.Map{"message": fmt.Sprintf("%d pacientes importados com sucesso", successCount)})
}

func (h *PatientHandler) DownloadTemplate(c *fiber.Ctx) error {
	c.Set("Content-Type", "text/csv")
	c.Set("Content-Disposition", "attachment; filename=\"template_pacientes.csv\"")
	return c.SendString("Nome,CPF,Email,Telefone,CEP,Rua,Bairro,Numero,Plano de Saude\nJoão Silva,12345678901,joao@email.com,11999999999,01000-000,Rua A,Centro,123,Amil")
}

func (h *PatientHandler) Export(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	patients, err := h.patientService.ListPatients(clinicID, "", 0)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar pacientes para exportação"})
	}

	c.Set("Content-Type", "text/csv")
	c.Set("Content-Disposition", "attachment; filename=\"pacientes.csv\"")

	writer := csv.NewWriter(c.Response().BodyWriter())
	defer writer.Flush()

	if err := writer.Write([]string{"Nome", "CPF", "Email", "Telefone", "CEP", "Rua", "Bairro", "Numero", "Plano de Saude", "Data de Cadastro"}); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao gerar arquivo CSV"})
	}

	for _, p := range patients {
		row := []string{
			p.Name,
			p.CPF,
			p.Email,
			p.Phone,
			p.Cep,
			p.Street,
			p.Neighborhood,
			p.Number,
			p.HealthInsurance,
			p.CreatedAt.Format("2006-01-02 15:04:05"),
		}
		if err := writer.Write(row); err != nil {
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao escrever linha CSV"})
		}
	}

	return nil
}

func (h *PatientHandler) GetByID(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	patient, err := h.patientService.GetPatient(clinicID, uint(id))
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(patient)
}
