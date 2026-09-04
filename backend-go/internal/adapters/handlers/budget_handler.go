package handlers

import (
	"strconv"
	"time"

	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/core/services"

	"github.com/gofiber/fiber/v2"
)

type BudgetHandler struct {
	service *services.BudgetService
}

func NewBudgetHandler() *BudgetHandler {
	return &BudgetHandler{
		service: services.NewBudgetService(),
	}
}

type CreateBudgetItemRequest struct {
	ProcedureID *uint  `json:"procedure_id"`
	ToothNumber *int   `json:"tooth_number"`
	Face        string `json:"face"`
	PriceCents  int64  `json:"price_cents"`
	Quantity    int    `json:"quantity"`
}

type CreateBudgetRequest struct {
	PatientID     uint                      `json:"patient_id"`
	DentistID     *uint                     `json:"dentist_id"`
	DiscountCents int64                     `json:"discount_cents"`
	Notes         string                    `json:"notes"`
	Items         []CreateBudgetItemRequest `json:"items"`
}

type ApproveBudgetRequest struct {
	TotalInstallments int    `json:"total_installments"`
	PaymentMethod     string `json:"payment_method"`
	DueDate           string `json:"due_date"`
}

func (h *BudgetHandler) List(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	var patientID *uint
	if pIDStr := c.Query("patient_id"); pIDStr != "" {
		if pID, err := strconv.ParseUint(pIDStr, 10, 32); err == nil {
			parsedID := uint(pID)
			patientID = &parsedID
		}
	}
	status := c.Query("status")

	budgets, err := h.service.ListBudgets(clinicID, patientID, status)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}
	return c.JSON(budgets)
}

func (h *BudgetHandler) GetByID(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	idStr := c.Params("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "ID inválido"})
	}

	budget, err := h.service.GetBudget(uint(id), clinicID)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
	}

	return c.JSON(budget)
}

func (h *BudgetHandler) Create(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	var req CreateBudgetRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Payload inválido"})
	}

	items := make([]domain.BudgetItem, len(req.Items))
	for i, item := range req.Items {
		items[i] = domain.BudgetItem{
			ProcedureID: item.ProcedureID,
			ToothNumber: item.ToothNumber,
			Face:        item.Face,
			PriceCents:  item.PriceCents,
			Quantity:    item.Quantity,
		}
	}

	budget, err := h.service.CreateBudget(clinicID, req.PatientID, req.DentistID, items, req.DiscountCents, req.Notes)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(budget)
}

func (h *BudgetHandler) Approve(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	idStr := c.Params("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "ID inválido"})
	}

	var req ApproveBudgetRequest
	_ = c.BodyParser(&req) // opcional

	var firstDueDate *time.Time
	if req.DueDate != "" {
		if t, err := time.Parse("2006-01-02", req.DueDate); err == nil {
			firstDueDate = &t
		}
	}

	budget, tx, err := h.service.ApproveBudget(uint(id), clinicID, req.TotalInstallments, req.PaymentMethod, firstDueDate)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
	}

	return c.JSON(fiber.Map{
		"message":     "Orçamento aprovado com sucesso e lançamento financeiro gerado",
		"budget":      budget,
		"transaction": tx,
	})
}

func (h *BudgetHandler) Reject(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	idStr := c.Params("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "ID inválido"})
	}

	budget, err := h.service.RejectBudget(uint(id), clinicID)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
	}

	return c.JSON(fiber.Map{
		"message": "Orçamento rejeitado com sucesso",
		"budget":  budget,
	})
}
