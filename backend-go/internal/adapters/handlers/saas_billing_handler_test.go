package handlers

import (
	"bytes"
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"encoding/json"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
)

func setupTestDB() {
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{})
	if err != nil {
		panic("Failed to connect database")
	}

	// Migrate the schema
	db.AutoMigrate(&domain.Clinic{})

	database.DB = db
}

func TestChangePlan(t *testing.T) {
	setupTestDB()

	// Inserir clínica fake
	fakeClinic := domain.Clinic{
		Name:      "Fake Clinic",
		CNPJ:      "12345678000100",
		Plan:      "basic",
		Status:    "active",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
	database.DB.Create(&fakeClinic)

	app := fiber.New()
	handler := NewSaaSHandler()

	app.Post("/change-plan", func(c *fiber.Ctx) error {
		// Mock local context variables as middleware would do
		c.Locals("clinic_id", float64(fakeClinic.ID))
		return handler.ChangePlan(c)
	})

	// Setup req body
	reqBody := map[string]string{"plan": "premium"}
	bodyBytes, _ := json.Marshal(reqBody)
	req := httptest.NewRequest("POST", "/change-plan", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatalf("Erro no request: %v", err)
	}

	if resp.StatusCode != 200 {
		t.Errorf("Esperava status 200, recebi %d", resp.StatusCode)
	}

	var updatedClinic domain.Clinic
	database.DB.First(&updatedClinic, fakeClinic.ID)
	if updatedClinic.Plan != "premium" {
		t.Errorf("Plano no foi atualizado para premium, atual: %s", updatedClinic.Plan)
	}
}
