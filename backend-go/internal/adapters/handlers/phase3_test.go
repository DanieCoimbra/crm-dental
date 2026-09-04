package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"os"
	"testing"

	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"dental-crm-api/internal/pkg/encryption"

	"github.com/glebarez/sqlite"
	"github.com/gofiber/fiber/v2"
	"gorm.io/gorm"
)

func setupPhase3TestDB(t *testing.T) {
	os.Setenv("APP_KEY", "base64:MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
	err := encryption.Init()
	if err != nil {
		t.Fatalf("Failed to init encryption: %v", err)
	}

	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{})
	if err != nil {
		t.Fatalf("Failed to connect test database: %v", err)
	}

	err = db.AutoMigrate(
		&domain.Clinic{},
		&domain.User{},
		&domain.Patient{},
		&domain.Procedure{},
		&domain.Budget{},
		&domain.BudgetItem{},
		&domain.ClinicTransaction{},
		&domain.ClinicInstallment{},
	)
	if err != nil {
		t.Fatalf("Failed to auto migrate test database: %v", err)
	}

	database.DB = db
}

func TestProcedureCRUD(t *testing.T) {
	setupPhase3TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Teste Procedimentos", CNPJ: "12.345.678/0001-90"}
	database.DB.Create(&clinic)

	procedureHandler := NewProcedureHandler()

	app := fiber.New()
	app.Use(func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		return c.Next()
	})

	app.Get("/procedures", procedureHandler.List)
	app.Post("/procedures", procedureHandler.Create)
	app.Put("/procedures/:id", procedureHandler.Update)
	app.Delete("/procedures/:id", procedureHandler.Delete)

	// 1. Create Procedure
	createReq := map[string]interface{}{
		"name":             "Restauração Resina",
		"description":      "Restauração estática em resina composta",
		"base_price_cents": 25000,
		"duration_minutes": 45,
		"color":            "#2563EB",
	}
	bodyBytes, _ := json.Marshal(createReq)
	req := httptest.NewRequest("POST", "/procedures", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatalf("POST /procedures error: %v", err)
	}
	if resp.StatusCode != 201 {
		t.Fatalf("Expected status 201, got %d", resp.StatusCode)
	}

	var createdProc domain.Procedure
	json.NewDecoder(resp.Body).Decode(&createdProc)
	if createdProc.ID == 0 {
		t.Fatalf("Expected procedure ID > 0")
	}
	if createdProc.Name != "Restauração Resina" {
		t.Errorf("Expected Name Restauração Resina, got %s", createdProc.Name)
	}
	if createdProc.BasePriceCents != 25000 {
		t.Errorf("Expected base_price_cents 25000, got %d", createdProc.BasePriceCents)
	}

	// 2. List Procedures
	reqList := httptest.NewRequest("GET", "/procedures", nil)
	respList, _ := app.Test(reqList, -1)
	if respList.StatusCode != 200 {
		t.Fatalf("Expected status 200 for GET /procedures, got %d", respList.StatusCode)
	}
	var list []domain.Procedure
	json.NewDecoder(respList.Body).Decode(&list)
	if len(list) != 1 {
		t.Fatalf("Expected 1 procedure in list, got %d", len(list))
	}

	// 3. Update Procedure
	updateReq := map[string]interface{}{
		"name":             "Restauração Resina Fotopolimerizável",
		"description":      "Atualizado",
		"base_price_cents": 30000,
		"duration_minutes": 60,
		"color":            "#10B981",
	}
	upBytes, _ := json.Marshal(updateReq)
	reqUp := httptest.NewRequest("PUT", fmt.Sprintf("/procedures/%d", createdProc.ID), bytes.NewReader(upBytes))
	reqUp.Header.Set("Content-Type", "application/json")
	respUp, _ := app.Test(reqUp, -1)
	if respUp.StatusCode != 200 {
		t.Fatalf("Expected status 200 for PUT /procedures/:id, got %d", respUp.StatusCode)
	}
	var updatedProc domain.Procedure
	json.NewDecoder(respUp.Body).Decode(&updatedProc)
	if updatedProc.Name != "Restauração Resina Fotopolimerizável" {
		t.Errorf("Expected updated name, got %s", updatedProc.Name)
	}
	if updatedProc.BasePriceCents != 30000 {
		t.Errorf("Expected updated price 30000, got %d", updatedProc.BasePriceCents)
	}

	// 4. Delete Procedure
	reqDel := httptest.NewRequest("DELETE", fmt.Sprintf("/procedures/%d", createdProc.ID), nil)
	respDel, _ := app.Test(reqDel, -1)
	if respDel.StatusCode != 200 {
		t.Fatalf("Expected status 200 for DELETE /procedures/:id, got %d", respDel.StatusCode)
	}

	// Verify deletion
	reqListAfter := httptest.NewRequest("GET", "/procedures", nil)
	respListAfter, _ := app.Test(reqListAfter, -1)
	var listAfter []domain.Procedure
	json.NewDecoder(respListAfter.Body).Decode(&listAfter)
	if len(listAfter) != 0 {
		t.Errorf("Expected 0 procedures after delete, got %d", len(listAfter))
	}
}

func TestBudgetLifecycleAndApproval(t *testing.T) {
	setupPhase3TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Orçamentos", CNPJ: "99.888.777/0001-66"}
	database.DB.Create(&clinic)

	patient := domain.Patient{ClinicID: clinic.ID, Name: "Pedro Orçamento", CPF: "123", Phone: "456"}
	database.DB.Create(&patient)

	proc := domain.Procedure{ClinicID: clinic.ID, Name: "Tratamento de Canal", BasePriceCents: 80000}
	database.DB.Create(&proc)

	budgetHandler := NewBudgetHandler()

	app := fiber.New()
	app.Use(func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		return c.Next()
	})

	app.Get("/budgets", budgetHandler.List)
	app.Post("/budgets", budgetHandler.Create)
	app.Get("/budgets/:id", budgetHandler.GetByID)
	app.Post("/budgets/:id/approve", budgetHandler.Approve)
	app.Post("/budgets/:id/reject", budgetHandler.Reject)

	// 1. Create Budget
	createReq := map[string]interface{}{
		"patient_id":     patient.ID,
		"discount_cents": 10000,
		"notes":          "Desconto concedido para pagamento facilitado",
		"items": []map[string]interface{}{
			{
				"procedure_id": proc.ID,
				"tooth_number": 46,
				"face":         "OCLUSAL",
				"price_cents":  80000,
				"quantity":     1,
			},
		},
	}
	bodyBytes, _ := json.Marshal(createReq)
	req := httptest.NewRequest("POST", "/budgets", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatalf("POST /budgets error: %v", err)
	}
	if resp.StatusCode != 201 {
		t.Fatalf("Expected status 201, got %d", resp.StatusCode)
	}

	var createdBudget domain.Budget
	json.NewDecoder(resp.Body).Decode(&createdBudget)

	if createdBudget.ID == 0 {
		t.Fatalf("Expected budget ID > 0")
	}
	if createdBudget.TotalAmountCents != 80000 {
		t.Errorf("Expected TotalAmountCents 80000, got %d", createdBudget.TotalAmountCents)
	}
	if createdBudget.DiscountCents != 10000 {
		t.Errorf("Expected DiscountCents 10000, got %d", createdBudget.DiscountCents)
	}
	if createdBudget.FinalAmountCents != 70000 {
		t.Errorf("Expected FinalAmountCents 70000, got %d", createdBudget.FinalAmountCents)
	}
	if createdBudget.Status != domain.BudgetStatusDraft {
		t.Errorf("Expected status 'draft', got %s", createdBudget.Status)
	}

	// 2. Get Budget Details
	reqGet := httptest.NewRequest("GET", fmt.Sprintf("/budgets/%d", createdBudget.ID), nil)
	respGet, _ := app.Test(reqGet, -1)
	if respGet.StatusCode != 200 {
		t.Fatalf("Expected status 200 for GET /budgets/:id, got %d", respGet.StatusCode)
	}
	var fetchedBudget domain.Budget
	json.NewDecoder(respGet.Body).Decode(&fetchedBudget)
	if len(fetchedBudget.Items) != 1 {
		t.Errorf("Expected 1 item in fetched budget, got %d", len(fetchedBudget.Items))
	}

	// 3. Approve Budget with 2 Installments
	approveReq := map[string]interface{}{
		"total_installments": 2,
		"payment_method":     "PIX",
		"due_date":           "2026-10-01",
	}
	appBytes, _ := json.Marshal(approveReq)
	reqApprove := httptest.NewRequest("POST", fmt.Sprintf("/budgets/%d/approve", createdBudget.ID), bytes.NewReader(appBytes))
	reqApprove.Header.Set("Content-Type", "application/json")
	respApprove, _ := app.Test(reqApprove, -1)

	if respApprove.StatusCode != 200 {
		t.Fatalf("Expected status 200 for POST /budgets/:id/approve, got %d", respApprove.StatusCode)
	}

	var approveResult struct {
		Message     string                 `json:"message"`
		Budget      domain.Budget          `json:"budget"`
		Transaction domain.ClinicTransaction `json:"transaction"`
	}
	json.NewDecoder(respApprove.Body).Decode(&approveResult)

	if approveResult.Budget.Status != domain.BudgetStatusApproved {
		t.Errorf("Expected approved status, got %s", approveResult.Budget.Status)
	}
	if approveResult.Transaction.ID == 0 {
		t.Fatalf("Expected financial transaction to be created")
	}
	if approveResult.Transaction.TotalAmount != 70000 {
		t.Errorf("Expected transaction total amount 70000, got %d", approveResult.Transaction.TotalAmount)
	}

	// Verify installments created in DB
	var installments []domain.ClinicInstallment
	database.DB.Where("transaction_id = ?", approveResult.Transaction.ID).Order("number asc").Find(&installments)

	if len(installments) != 2 {
		t.Fatalf("Expected 2 installments created in DB, got %d", len(installments))
	}
	if installments[0].AmountCents+installments[1].AmountCents != 70000 {
		t.Errorf("Expected sum of installments to be 70000, got %d", installments[0].AmountCents+installments[1].AmountCents)
	}
}

func TestBudgetRejection(t *testing.T) {
	setupPhase3TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Rejeição", CNPJ: "88.777.666/0001-55"}
	database.DB.Create(&clinic)
	patient := domain.Patient{ClinicID: clinic.ID, Name: "Marcos Rejeito", CPF: "111", Phone: "222"}
	database.DB.Create(&patient)

	budgetHandler := NewBudgetHandler()

	app := fiber.New()
	app.Use(func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		return c.Next()
	})

	app.Post("/budgets", budgetHandler.Create)
	app.Post("/budgets/:id/reject", budgetHandler.Reject)

	// Create budget
	createReq := map[string]interface{}{
		"patient_id": patient.ID,
		"items": []map[string]interface{}{
			{"price_cents": 50000, "quantity": 1},
		},
	}
	bodyBytes, _ := json.Marshal(createReq)
	req := httptest.NewRequest("POST", "/budgets", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	resp, _ := app.Test(req, -1)

	var createdBudget domain.Budget
	json.NewDecoder(resp.Body).Decode(&createdBudget)

	// Reject budget
	reqReject := httptest.NewRequest("POST", fmt.Sprintf("/budgets/%d/reject", createdBudget.ID), nil)
	respReject, _ := app.Test(reqReject, -1)
	if respReject.StatusCode != 200 {
		t.Fatalf("Expected status 200 for POST /budgets/:id/reject, got %d", respReject.StatusCode)
	}

	var rejectResult struct {
		Budget domain.Budget `json:"budget"`
	}
	json.NewDecoder(respReject.Body).Decode(&rejectResult)

	if rejectResult.Budget.Status != domain.BudgetStatusRejected {
		t.Errorf("Expected budget status rejected, got %s", rejectResult.Budget.Status)
	}
}

func TestFinancialPayInstallmentAndCashFlow(t *testing.T) {
	setupPhase3TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Fluxo Financeiro", CNPJ: "77.666.555/0001-44"}
	database.DB.Create(&clinic)
	patient := domain.Patient{ClinicID: clinic.ID, Name: "Julia Financeiro", CPF: "333", Phone: "444"}
	database.DB.Create(&patient)

	financialHandler := NewFinancialHandler()

	app := fiber.New()
	app.Use(func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		return c.Next()
	})

	app.Post("/financial/transactions", financialHandler.CreateTransaction)
	app.Post("/financial/installments/:id/pay", financialHandler.PayInstallment)
	app.Get("/financial/cash-flow", financialHandler.GetCashFlow)

	// 1. Create Income Transaction with 2 installments of 50000 each (Total: 100000)
	txReq := map[string]interface{}{
		"patient_id":         patient.ID,
		"type":               "income",
		"category":           "Procedimento",
		"description":        "Ortodontia",
		"total_amount_cents": 100000,
		"payment_method":     "Cartão de Crédito",
		"due_date":           "2026-09-04",
		"total_installments": 2,
	}
	txBytes, _ := json.Marshal(txReq)
	reqTx := httptest.NewRequest("POST", "/financial/transactions", bytes.NewReader(txBytes))
	reqTx.Header.Set("Content-Type", "application/json")
	respTx, err := app.Test(reqTx, -1)
	if err != nil {
		t.Fatalf("POST /financial/transactions error: %v", err)
	}
	if respTx.StatusCode != 201 {
		t.Fatalf("Expected status 201, got %d", respTx.StatusCode)
	}

	var createdTx domain.ClinicTransaction
	json.NewDecoder(respTx.Body).Decode(&createdTx)

	if len(createdTx.Installments) != 2 {
		t.Fatalf("Expected 2 installments created, got %d", len(createdTx.Installments))
	}

	firstInstID := createdTx.Installments[0].ID

	// 2. Pay first installment via POST /financial/installments/:id/pay
	reqPay := httptest.NewRequest("POST", fmt.Sprintf("/financial/installments/%d/pay", firstInstID), nil)
	respPay, _ := app.Test(reqPay, -1)
	if respPay.StatusCode != 200 {
		t.Fatalf("Expected status 200 for paying installment, got %d", respPay.StatusCode)
	}

	// 3. Get Cash Flow Report
	reqCF := httptest.NewRequest("GET", "/financial/cash-flow", nil)
	respCF, _ := app.Test(reqCF, -1)
	if respCF.StatusCode != 200 {
		t.Fatalf("Expected status 200 for GET /financial/cash-flow, got %d", respCF.StatusCode)
	}

	var cfReport struct {
		TotalIncomeCents   int64 `json:"total_income_cents"`
		TotalExpenseCents  int64 `json:"total_expense_cents"`
		TotalReceivedCents int64 `json:"total_received_cents"`
		TotalPendingCents  int64 `json:"total_pending_cents"`
		TotalOverdueCents  int64 `json:"total_overdue_cents"`
		NetBalanceCents    int64 `json:"net_balance_cents"`
	}
	json.NewDecoder(respCF.Body).Decode(&cfReport)

	if cfReport.TotalIncomeCents != 100000 {
		t.Errorf("Expected TotalIncomeCents 100000, got %d", cfReport.TotalIncomeCents)
	}
	if cfReport.TotalReceivedCents != 50000 {
		t.Errorf("Expected TotalReceivedCents 50000, got %d", cfReport.TotalReceivedCents)
	}
}
