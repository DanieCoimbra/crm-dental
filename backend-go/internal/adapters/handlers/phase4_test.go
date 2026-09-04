package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"dental-crm-api/internal/pkg/encryption"

	"github.com/glebarez/sqlite"
	"github.com/gofiber/fiber/v2"
	"gorm.io/gorm"
)

func setupPhase4TestDB(t *testing.T) {
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
		&domain.MedicalDocument{},
		&domain.AuditLog{},
		&domain.Appointment{},
		&domain.ClinicTransaction{},
		&domain.ClinicInstallment{},
	)
	if err != nil {
		t.Fatalf("Failed to auto migrate test database: %v", err)
	}

	database.DB = db
}

func TestMedicalDocumentCRUD(t *testing.T) {
	setupPhase4TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Teste Documentos", CNPJ: "12.345.678/0001-99"}
	database.DB.Create(&clinic)

	dentist := domain.User{ClinicID: clinic.ID, Name: "Dr. Roberto Santos", Email: "roberto@clinica.com"}
	database.DB.Create(&dentist)

	patient := domain.Patient{ClinicID: clinic.ID, Name: "Ana Maria Silva", CPF: "123.456.789-00", Phone: "(11) 98765-4321"}
	database.DB.Create(&patient)

	docHandler := NewMedicalDocumentHandler()

	app := fiber.New()
	app.Use(func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(dentist.ID))
		return c.Next()
	})

	app.Get("/patients/:id/documents", docHandler.ListByPatient)
	app.Post("/patients/:id/documents", docHandler.Create)
	app.Get("/documents/:id", docHandler.GetByID)
	app.Delete("/documents/:id", docHandler.Delete)

	// 1. Criar Atestado Odontológico
	createAtestadoReq := map[string]interface{}{
		"type":    "ATESTADO",
		"title":   "Atestado de Afastamento - 2 dias",
		"content": "Atesto para os devidos fins que o paciente necessita de 2 dias de repouso por motivo de cirurgia siso.",
	}
	bodyBytes, _ := json.Marshal(createAtestadoReq)
	req := httptest.NewRequest("POST", fmt.Sprintf("/patients/%d/documents", patient.ID), bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatalf("POST /patients/:id/documents (ATESTADO) error: %v", err)
	}
	if resp.StatusCode != 201 {
		t.Fatalf("Expected status 201, got %d", resp.StatusCode)
	}

	var createdDoc domain.MedicalDocument
	json.NewDecoder(resp.Body).Decode(&createdDoc)
	if createdDoc.ID == 0 {
		t.Fatalf("Expected document ID > 0")
	}
	if createdDoc.Type != "ATESTADO" {
		t.Errorf("Expected type ATESTADO, got %s", createdDoc.Type)
	}
	if createdDoc.Title != "Atestado de Afastamento - 2 dias" {
		t.Errorf("Expected title 'Atestado de Afastamento - 2 dias', got %s", createdDoc.Title)
	}
	if createdDoc.Content != "Atesto para os devidos fins que o paciente necessita de 2 dias de repouso por motivo de cirurgia siso." {
		t.Errorf("Expected decrypted content, got %s", createdDoc.Content)
	}

	// 2. Verificar Criptografia direta no Banco de Dados
	var rawContent string
	database.DB.Table("medical_documents").Select("content_encrypted").Where("id = ?", createdDoc.ID).Scan(&rawContent)
	if rawContent == createdDoc.Content {
		t.Errorf("Content should be encrypted in DB, but matches plain content")
	}

	// 3. Criar Receita Médica
	createReceitaReq := map[string]interface{}{
		"type":    "RECEITA",
		"title":   "Receituário Amoxicilina + Dipirona",
		"content": "1. Amoxicilina 500mg - 1 cx - Tomar 1 cp de 8 em 8 horas por 7 dias.\n2. Dipirona 500mg - 1 cx - Tomar 1 cp se houver dor.",
	}
	recBytes, _ := json.Marshal(createReceitaReq)
	reqRec := httptest.NewRequest("POST", fmt.Sprintf("/patients/%d/documents", patient.ID), bytes.NewReader(recBytes))
	reqRec.Header.Set("Content-Type", "application/json")
	respRec, _ := app.Test(reqRec, -1)
	if respRec.StatusCode != 201 {
		t.Fatalf("Expected status 201 for Receita, got %d", respRec.StatusCode)
	}

	// 4. Criar Encaminhamento
	createEncaminhamentoReq := map[string]interface{}{
		"type":    "ENCAMINHAMENTO",
		"title":   "Encaminhamento Endodontia dente 36",
		"content": "Encaminho paciente para tratamento endodôntico do elemento 36 devido à pulpite irreversível.",
	}
	encBytes, _ := json.Marshal(createEncaminhamentoReq)
	reqEnc := httptest.NewRequest("POST", fmt.Sprintf("/patients/%d/documents", patient.ID), bytes.NewReader(encBytes))
	reqEnc.Header.Set("Content-Type", "application/json")
	respEnc, _ := app.Test(reqEnc, -1)
	if respEnc.StatusCode != 201 {
		t.Fatalf("Expected status 201 for Encaminhamento, got %d", respEnc.StatusCode)
	}

	// 5. Listar Documentos do Paciente
	reqList := httptest.NewRequest("GET", fmt.Sprintf("/patients/%d/documents", patient.ID), nil)
	respList, _ := app.Test(reqList, -1)
	if respList.StatusCode != 200 {
		t.Fatalf("Expected status 200 for ListByPatient, got %d", respList.StatusCode)
	}
	var docsList []domain.MedicalDocument
	json.NewDecoder(respList.Body).Decode(&docsList)
	if len(docsList) != 3 {
		t.Fatalf("Expected 3 documents for patient, got %d", len(docsList))
	}

	// 6. Obter Documento por ID
	reqGet := httptest.NewRequest("GET", fmt.Sprintf("/documents/%d", createdDoc.ID), nil)
	respGet, _ := app.Test(reqGet, -1)
	if respGet.StatusCode != 200 {
		t.Fatalf("Expected status 200 for GetByID, got %d", respGet.StatusCode)
	}
	var fetchedDoc domain.MedicalDocument
	json.NewDecoder(respGet.Body).Decode(&fetchedDoc)
	if fetchedDoc.ID != createdDoc.ID {
		t.Errorf("Expected doc ID %d, got %d", createdDoc.ID, fetchedDoc.ID)
	}
	if fetchedDoc.Content != createdDoc.Content {
		t.Errorf("Expected fetched doc content to match, got %s", fetchedDoc.Content)
	}

	// 7. Excluir Documento
	reqDel := httptest.NewRequest("DELETE", fmt.Sprintf("/documents/%d", createdDoc.ID), nil)
	respDel, _ := app.Test(reqDel, -1)
	if respDel.StatusCode != 200 {
		t.Fatalf("Expected status 200 for Delete, got %d", respDel.StatusCode)
	}

	// Verificar se foi excluído
	reqGetAfter := httptest.NewRequest("GET", fmt.Sprintf("/documents/%d", createdDoc.ID), nil)
	respGetAfter, _ := app.Test(reqGetAfter, -1)
	if respGetAfter.StatusCode != 404 {
		t.Errorf("Expected status 404 after deletion, got %d", respGetAfter.StatusCode)
	}
}

func TestMedicalDocumentValidationErrors(t *testing.T) {
	setupPhase4TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Teste Validações", CNPJ: "99.999.999/0001-99"}
	database.DB.Create(&clinic)
	dentist := domain.User{ClinicID: clinic.ID, Name: "Dr. Validação"}
	database.DB.Create(&dentist)
	patient := domain.Patient{ClinicID: clinic.ID, Name: "Paciente Teste"}
	database.DB.Create(&patient)

	docHandler := NewMedicalDocumentHandler()

	app := fiber.New()
	app.Use(func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(dentist.ID))
		return c.Next()
	})

	app.Post("/patients/:id/documents", docHandler.Create)

	// Tipo inválido
	invalidTypeReq := map[string]interface{}{
		"type":    "INVALID_TYPE",
		"title":   "Teste",
		"content": "Conteúdo",
	}
	b1, _ := json.Marshal(invalidTypeReq)
	req1 := httptest.NewRequest("POST", fmt.Sprintf("/patients/%d/documents", patient.ID), bytes.NewReader(b1))
	req1.Header.Set("Content-Type", "application/json")
	resp1, _ := app.Test(req1, -1)
	if resp1.StatusCode != 400 {
		t.Errorf("Expected status 400 for invalid type, got %d", resp1.StatusCode)
	}

	// Título em branco
	blankTitleReq := map[string]interface{}{
		"type":    "ATESTADO",
		"title":   "",
		"content": "Conteúdo",
	}
	b2, _ := json.Marshal(blankTitleReq)
	req2 := httptest.NewRequest("POST", fmt.Sprintf("/patients/%d/documents", patient.ID), bytes.NewReader(b2))
	req2.Header.Set("Content-Type", "application/json")
	resp2, _ := app.Test(req2, -1)
	if resp2.StatusCode != 400 {
		t.Errorf("Expected status 400 for empty title, got %d", resp2.StatusCode)
	}
}

func TestDashboardStats(t *testing.T) {
	setupPhase4TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Dashboard Teste", CNPJ: "33.333.333/0001-33"}
	database.DB.Create(&clinic)

	patient1 := domain.Patient{ClinicID: clinic.ID, Name: "Paciente 1"}
	database.DB.Create(&patient1)
	patient2 := domain.Patient{ClinicID: clinic.ID, Name: "Paciente 2"}
	database.DB.Create(&patient2)

	// Appointment today
	today := time.Now()
	endTime := today.Add(30 * time.Minute)
	app1 := domain.Appointment{
		ClinicID:  clinic.ID,
		PatientID: patient1.ID,
		StartTime: &today,
		EndTime:   &endTime,
		Status:    "confirmed",
	}
	database.DB.Create(&app1)

	// Paid transaction / installment for revenue calculation
	tx := domain.ClinicTransaction{
		ClinicID:    clinic.ID,
		Type:        "income",
		TotalAmount: 50000,
	}
	database.DB.Create(&tx)

	inst := domain.ClinicInstallment{
		ClinicID:      clinic.ID,
		TransactionID: tx.ID,
		Number:        1,
		AmountCents:   50000,
		Status:        "paid",
		UpdatedAt:     today,
	}
	database.DB.Create(&inst)

	dashboardHandler := NewDashboardHandler()

	app := fiber.New()
	app.Use(func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		return c.Next()
	})

	app.Get("/dashboard/stats", dashboardHandler.GetStats)

	req := httptest.NewRequest("GET", "/dashboard/stats", nil)
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatalf("GET /dashboard/stats error: %v", err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("Expected status 200, got %d", resp.StatusCode)
	}

	var stats map[string]interface{}
	json.NewDecoder(resp.Body).Decode(&stats)

	if stats["totalPatients"] == nil {
		t.Errorf("Expected totalPatients in dashboard stats")
	}
	if stats["newPatientsThisMonth"] == nil {
		t.Errorf("Expected newPatientsThisMonth in dashboard stats")
	}
	if stats["appointmentsToday"] == nil {
		t.Errorf("Expected appointmentsToday in dashboard stats")
	}
	if stats["returnRate"] == nil {
		t.Errorf("Expected returnRate in dashboard stats")
	}
	if stats["cancellationRate"] == nil {
		t.Errorf("Expected cancellationRate in dashboard stats")
	}
	if stats["monthlyRevenue"] == nil {
		t.Errorf("Expected monthlyRevenue in dashboard stats")
	}
	if stats["weeklyData"] == nil {
		t.Errorf("Expected weeklyData in dashboard stats")
	}
	if stats["revenueData"] == nil {
		t.Errorf("Expected revenueData in dashboard stats")
	}
}
