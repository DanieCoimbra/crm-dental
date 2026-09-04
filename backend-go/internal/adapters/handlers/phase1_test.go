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

func setupPhase1TestDB(t *testing.T) {
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
		&domain.TeethStatus{},
		&domain.TeethHistory{},
		&domain.ClinicalNote{},
		&domain.PatientFile{},
		&domain.AuditLog{},
	)
	if err != nil {
		t.Fatalf("Failed to auto migrate test database: %v", err)
	}

	database.DB = db
}

func TestF01PatientsDB(t *testing.T) {
	setupPhase1TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Teste F01", CNPJ: "11.111.111/0001-11"}
	database.DB.Create(&clinic)

	patientHandler := NewPatientHandler()

	app := fiber.New()
	app.Post("/patients", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(1))
		return patientHandler.Create(c)
	})
	app.Get("/patients/:id", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(1))
		return patientHandler.GetByID(c)
	})

	reqBody := map[string]interface{}{
		"full_name":        "Maria Silva",
		"cpf":              "123.456.789-00",
		"phone":            "11988887777",
		"email":            "maria@teste.com",
		"notes":            "Sensibilidade nos dentes",
		"health_insurance": "Amil",
	}
	bodyBytes, _ := json.Marshal(reqBody)
	req := httptest.NewRequest("POST", "/patients", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatalf("Req error: %v", err)
	}
	if resp.StatusCode != 201 {
		t.Fatalf("Expected status 201, got %d", resp.StatusCode)
	}

	var createdPatient domain.Patient
	json.NewDecoder(resp.Body).Decode(&createdPatient)

	if createdPatient.ID == 0 {
		t.Fatalf("Expected patient ID to be > 0")
	}
	if createdPatient.Name != "Maria Silva" {
		t.Errorf("Expected Name Maria Silva, got %s", createdPatient.Name)
	}
	if createdPatient.CPF != "123.456.789-00" {
		t.Errorf("Expected decrypted CPF in struct memory, got %s", createdPatient.CPF)
	}

	var dbCPF, dbPhone string
	row := database.DB.Table("patients").Where("id = ?", createdPatient.ID).Select("cpf_encrypted, phone_encrypted").Row()
	row.Scan(&dbCPF, &dbPhone)

	if dbCPF == "123.456.789-00" || dbCPF == "" {
		t.Errorf("Expected CPF to be encrypted in DB, got %s", dbCPF)
	}
	if dbPhone == "11988887777" || dbPhone == "" {
		t.Errorf("Expected Phone to be encrypted in DB, got %s", dbPhone)
	}
}

func TestF02OdontogramDB(t *testing.T) {
	setupPhase1TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Odontograma Teste", CNPJ: "22.222.222/0001-22"}
	database.DB.Create(&clinic)
	patient := domain.Patient{ClinicID: clinic.ID, Name: "João Odonto", CPF: "111", Phone: "222"}
	database.DB.Create(&patient)

	teethHandler := NewTeethHandler()

	app := fiber.New()
	app.Post("/patients/:id/teeth", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(10))
		return teethHandler.UpdateTooth(c)
	})
	app.Get("/patients/:id/teeth/history", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(10))
		return teethHandler.GetHistory(c)
	})

	// 1. Test invalid FDI tooth
	invalidBody, _ := json.Marshal(map[string]interface{}{
		"tooth_number": 99,
		"condition":    "CARIADO",
	})
	reqInv := httptest.NewRequest("POST", fmt.Sprintf("/patients/%d/teeth", patient.ID), bytes.NewReader(invalidBody))
	reqInv.Header.Set("Content-Type", "application/json")
	respInv, _ := app.Test(reqInv, -1)
	if respInv.StatusCode != 400 {
		t.Errorf("Expected 400 for invalid FDI tooth, got %d", respInv.StatusCode)
	}

	// 2. Test valid FDI tooth status update
	validBody, _ := json.Marshal(map[string]interface{}{
		"tooth_number": 11,
		"face":         "MESIAL",
		"condition":    "CARIADO",
		"notes":        "Cárie mesial observada no dente 11",
	})
	reqVal := httptest.NewRequest("POST", fmt.Sprintf("/patients/%d/teeth", patient.ID), bytes.NewReader(validBody))
	reqVal.Header.Set("Content-Type", "application/json")
	respVal, _ := app.Test(reqVal, -1)

	if respVal.StatusCode != 201 {
		t.Fatalf("Expected 201 for valid tooth update, got %d", respVal.StatusCode)
	}

	// 3. Verify history recorded
	reqHist := httptest.NewRequest("GET", fmt.Sprintf("/patients/%d/teeth/history", patient.ID), nil)
	respHist, _ := app.Test(reqHist, -1)
	if respHist.StatusCode != 200 {
		t.Fatalf("Expected 200 for teeth history, got %d", respHist.StatusCode)
	}

	var history []domain.TeethHistory
	json.NewDecoder(respHist.Body).Decode(&history)

	if len(history) != 1 {
		t.Fatalf("Expected 1 history item, got %d", len(history))
	}
	if history[0].ToothNumber != 11 {
		t.Errorf("Expected history tooth_number 11, got %d", history[0].ToothNumber)
	}
	if history[0].NewCondition != domain.ConditionCariado {
		t.Errorf("Expected history condition CARIADO, got %s", history[0].NewCondition)
	}
}

func TestF03ClinicalRecordsDB(t *testing.T) {
	setupPhase1TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Prontuário", CNPJ: "33.333.333/0001-33"}
	database.DB.Create(&clinic)
	patient := domain.Patient{ClinicID: clinic.ID, Name: "Carlos Prontuário", CPF: "222", Phone: "333"}
	database.DB.Create(&patient)

	noteHandler := NewClinicalNoteHandler()
	fileHandler := NewPatientFileHandler()

	app := fiber.New()
	app.Post("/patients/:id/notes", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(5))
		return noteHandler.Create(c)
	})
	app.Get("/patients/:id/notes", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(5))
		return noteHandler.ListByPatient(c)
	})
	app.Post("/patients/:id/files", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(5))
		return fileHandler.Create(c)
	})

	// 1. Create Clinical Note
	now := time.Now()
	noteBody, _ := json.Marshal(map[string]interface{}{
		"attendance_date":   now,
		"chief_complaint":   "Dor no dente 46 ao mastigar",
		"diagnosis":         "Pulpite irreversível",
		"procedure_summary": "Iniciado tratamento de canal no dente 46 com selamento provisório",
	})

	reqNote := httptest.NewRequest("POST", fmt.Sprintf("/patients/%d/notes", patient.ID), bytes.NewReader(noteBody))
	reqNote.Header.Set("Content-Type", "application/json")
	respNote, _ := app.Test(reqNote, -1)

	if respNote.StatusCode != 201 {
		t.Fatalf("Expected 201 for creating clinical note, got %d", respNote.StatusCode)
	}

	var createdNote domain.ClinicalNote
	json.NewDecoder(respNote.Body).Decode(&createdNote)

	if createdNote.ID == 0 {
		t.Fatalf("Expected note ID > 0")
	}
	if createdNote.ProcedureSummary != "Iniciado tratamento de canal no dente 46 com selamento provisório" {
		t.Errorf("Procedure summary mismatch")
	}

	// Verify encryption in DB
	var dbChief string
	database.DB.Table("clinical_notes").Where("id = ?", createdNote.ID).Select("chief_complaint_encrypted").Row().Scan(&dbChief)
	if dbChief == "Dor no dente 46 ao mastigar" || dbChief == "" {
		t.Errorf("Expected chief_complaint to be encrypted in DB, got %s", dbChief)
	}

	// 2. Create Patient File via JSON
	fileBody, _ := json.Marshal(map[string]interface{}{
		"file_name": "radiografia_dente46.png",
		"file_url":  "https://storage.supabase.com/patients_files/rad46.png",
		"file_type": "RADIOGRAFIA",
		"file_size": 102400,
		"category":  "Radiografias",
	})
	reqFile := httptest.NewRequest("POST", fmt.Sprintf("/patients/%d/files", patient.ID), bytes.NewReader(fileBody))
	reqFile.Header.Set("Content-Type", "application/json")
	respFile, _ := app.Test(reqFile, -1)

	if respFile.StatusCode != 201 {
		t.Fatalf("Expected 201 for creating patient file, got %d", respFile.StatusCode)
	}

	var createdFile domain.PatientFile
	json.NewDecoder(respFile.Body).Decode(&createdFile)
	if createdFile.FileType != domain.FileTypeRadiografia {
		t.Errorf("Expected RADIOGRAFIA file_type, got %s", createdFile.FileType)
	}
}
