package handlers

import (
	"dental-crm-api/internal/core/services"

	"github.com/gofiber/fiber/v2"
	"strconv"
	"time"
)

type FinancialHandler struct {
	financialService *services.FinancialService
}

func NewFinancialHandler() *FinancialHandler {
	return &FinancialHandler{
		financialService: services.NewFinancialService(),
	}
}

type CreateTransactionRequest struct {
	PatientID         *uint  `json:"patient_id"`
	Type              string `json:"type"` // "income" or "expense"
	Category          string `json:"category"`
	Description       string `json:"description"`
	TotalAmountCents  int64  `json:"total_amount_cents"`
	PaymentMethod     string `json:"payment_method"`
	DueDate           string `json:"due_date"`
	TotalInstallments int    `json:"total_installments"`
}

func (h *FinancialHandler) CreateTransaction(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req CreateTransactionRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid request body"})
	}

	dueDate, err := time.Parse("2006-01-02", req.DueDate)
	if err != nil {
		dueDate = time.Now()
	}

	tx, err := h.financialService.CreateTransaction(
		clinicID,
		req.PatientID,
		req.Type,
		req.Category,
		req.Description,
		req.TotalAmountCents,
		req.PaymentMethod,
		dueDate,
		req.TotalInstallments,
	)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(tx)
}

func (h *FinancialHandler) GetTransactions(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	txType := c.Query("type")
	status := c.Query("status")

	transactions, err := h.financialService.GetTransactions(clinicID, txType, status)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to fetch transactions"})
	}

	return c.JSON(transactions)
}

func (h *FinancialHandler) PayInstallment(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	installmentIDStr := c.Params("id")
	installmentID, _ := strconv.ParseUint(installmentIDStr, 10, 32)

	if err := h.financialService.RegisterInstallmentPayment(uint(installmentID), clinicID); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
	}

	return c.JSON(fiber.Map{"message": "Parcela paga com sucesso"})
}
