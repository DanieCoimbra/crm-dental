package handlers

import (
	"dental-crm-api/internal/core/services"
	"fmt"
	"strconv"

	"github.com/gofiber/fiber/v2"
)

type ClinicalEvolutionHandler struct {
	evolutionService *services.ClinicalEvolutionService
	auditService     *services.AuditService
}

func NewClinicalEvolutionHandler() *ClinicalEvolutionHandler {
	return &ClinicalEvolutionHandler{
		evolutionService: services.NewClinicalEvolutionService(),
		auditService:     services.NewAuditService(),
	}
}

type CreateEvolutionRequest struct {
	ContentHtml string `json:"content_html"`
}

func (h *ClinicalEvolutionHandler) ListByPatient(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	userID := uint(c.Locals("user_id").(float64))
	patientID, err := strconv.Atoi(c.Params("patient_id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	page, _ := strconv.Atoi(c.Query("page", "1"))
	limit, _ := strconv.Atoi(c.Query("limit", "20"))

	evolutions, total, err := h.evolutionService.ListByPatient(clinicID, uint(patientID), page, limit)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar evoluções"})
	}

	// Registrar Log de Auditoria LGPD (Consulta a Prontuário)
	h.auditService.LogAction(
		clinicID,
		userID,
		"LIST_EVOLUTIONS",
		"Patient",
		uint(patientID),
		c.IP(),
		c.Get("User-Agent"),
		fmt.Sprintf("Visualizou prontuário eletrônico do paciente ID %d (total: %d registros)", patientID, total),
	)

	// Se não for solicitada paginação explícita no query, mantém compatibilidade retornando array direto
	if c.Query("page") == "" && c.Query("limit") == "" {
		return c.JSON(evolutions)
	}

	return c.JSON(fiber.Map{
		"data":  evolutions,
		"total": total,
		"page":  page,
		"limit": limit,
	})
}

func (h *ClinicalEvolutionHandler) Create(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	userID := uint(c.Locals("user_id").(float64))
	patientID, err := strconv.Atoi(c.Params("patient_id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID de paciente inválido"})
	}

	var req CreateEvolutionRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	evolution, err := h.evolutionService.CreateEvolution(clinicID, uint(patientID), userID, req.ContentHtml)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	// Registrar Log de Auditoria LGPD (Criação de Registro Clínico)
	h.auditService.LogAction(
		clinicID,
		userID,
		"CREATE_EVOLUTION",
		"ClinicalEvolution",
		evolution.ID,
		c.IP(),
		c.Get("User-Agent"),
		fmt.Sprintf("Criou nova evolução clínica para paciente ID %d", patientID),
	)

	return c.Status(fiber.StatusCreated).JSON(evolution)
}

func (h *ClinicalEvolutionHandler) Delete(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	userID := uint(c.Locals("user_id").(float64))
	id, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	err = h.evolutionService.DeleteEvolution(clinicID, uint(id))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	// Registrar Log de Auditoria LGPD (Exclusão de Registro Clínico)
	h.auditService.LogAction(
		clinicID,
		userID,
		"DELETE_EVOLUTION",
		"ClinicalEvolution",
		uint(id),
		c.IP(),
		c.Get("User-Agent"),
		fmt.Sprintf("Excluiu registro de evolução clínica ID %d", id),
	)

	return c.SendStatus(fiber.StatusNoContent)
}
