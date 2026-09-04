package handlers

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"dental-crm-api/internal/pkg/encryption"

	"github.com/glebarez/sqlite"
	"github.com/gofiber/fiber/v2"
	"gorm.io/gorm"
)

func setupPhase2TestDB(t *testing.T) {
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
		&domain.Room{},
		&domain.AppointmentType{},
		&domain.Appointment{},
		&domain.AuditLog{},
		&domain.Setting{},
	)
	if err != nil {
		t.Fatalf("Failed to auto migrate test database: %v", err)
	}

	database.DB = db
}

func TestAppointmentConfirm(t *testing.T) {
	setupPhase2TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Sorriso VIP", CNPJ: "10.000.000/0001-00"}
	database.DB.Create(&clinic)

	patient := domain.Patient{ClinicID: clinic.ID, Name: "Carlos Eduardo", CPF: "123.456.789-01", Phone: "11977776666"}
	database.DB.Create(&patient)

	doctor := domain.User{ClinicID: clinic.ID, Name: "Dr. Roberto", Email: "roberto@clinica.com"}
	database.DB.Create(&doctor)

	startTime := time.Now().Add(24 * time.Hour)
	endTime := startTime.Add(30 * time.Minute)

	appt := domain.Appointment{
		ClinicID:  clinic.ID,
		DoctorID:  doctor.ID,
		PatientID: patient.ID,
		StartTime: &startTime,
		EndTime:   &endTime,
		Status:    "scheduled",
		Notes:     "Consulta inicial de rotina",
	}
	database.DB.Create(&appt)

	appointmentHandler := NewAppointmentHandler()

	app := fiber.New()
	app.Post("/appointments/:id/confirm", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(doctor.ID))
		return appointmentHandler.Confirm(c)
	})

	req := httptest.NewRequest("POST", fmt.Sprintf("/appointments/%d/confirm", appt.ID), nil)
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatalf("Request failed: %v", err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("Expected status 200, got %d", resp.StatusCode)
	}

	var updatedAppt domain.Appointment
	json.NewDecoder(resp.Body).Decode(&updatedAppt)

	if updatedAppt.Status != "confirmed" {
		t.Errorf("Expected status 'confirmed', got '%s'", updatedAppt.Status)
	}

	var dbAppt domain.Appointment
	database.DB.First(&dbAppt, appt.ID)
	if dbAppt.Status != "confirmed" {
		t.Errorf("Expected DB status 'confirmed', got '%s'", dbAppt.Status)
	}
}

func TestAppointmentMiss(t *testing.T) {
	setupPhase2TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Dental Center", CNPJ: "20.000.000/0001-00"}
	database.DB.Create(&clinic)

	patient := domain.Patient{ClinicID: clinic.ID, Name: "Juliana Paes", CPF: "987.654.321-09", Phone: "11966665555"}
	database.DB.Create(&patient)

	doctor := domain.User{ClinicID: clinic.ID, Name: "Dra. Patricia", Email: "patricia@clinica.com"}
	database.DB.Create(&doctor)

	startTime := time.Now().Add(24 * time.Hour)
	endTime := startTime.Add(30 * time.Minute)

	appt := domain.Appointment{
		ClinicID:  clinic.ID,
		DoctorID:  doctor.ID,
		PatientID: patient.ID,
		StartTime: &startTime,
		EndTime:   &endTime,
		Status:    "scheduled",
	}
	database.DB.Create(&appt)

	appointmentHandler := NewAppointmentHandler()

	app := fiber.New()
	app.Post("/appointments/:id/miss", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(doctor.ID))
		return appointmentHandler.Miss(c)
	})

	req := httptest.NewRequest("POST", fmt.Sprintf("/appointments/%d/miss", appt.ID), nil)
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatalf("Request failed: %v", err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("Expected status 200, got %d", resp.StatusCode)
	}

	var updatedAppt domain.Appointment
	json.NewDecoder(resp.Body).Decode(&updatedAppt)

	if updatedAppt.Status != "missed" {
		t.Errorf("Expected status 'missed', got '%s'", updatedAppt.Status)
	}

	var dbAppt domain.Appointment
	database.DB.First(&dbAppt, appt.ID)
	if dbAppt.Status != "missed" {
		t.Errorf("Expected DB status 'missed', got '%s'", dbAppt.Status)
	}
}

func TestAppointmentWhatsAppLink(t *testing.T) {
	setupPhase2TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Odonto Riso", CNPJ: "30.000.000/0001-00"}
	database.DB.Create(&clinic)

	patient := domain.Patient{ClinicID: clinic.ID, Name: "Ana Souza", CPF: "111.222.333-44", Phone: "11999998888"}
	database.DB.Create(&patient)

	doctor := domain.User{ClinicID: clinic.ID, Name: "Dr. Lucas", Email: "lucas@clinica.com"}
	database.DB.Create(&doctor)

	appDate := time.Date(2026, time.October, 15, 14, 30, 0, 0, time.UTC)
	appEndDate := appDate.Add(45 * time.Minute)

	appt := domain.Appointment{
		ClinicID:  clinic.ID,
		DoctorID:  doctor.ID,
		PatientID: patient.ID,
		StartTime: &appDate,
		EndTime:   &appEndDate,
		Status:    "scheduled",
	}
	database.DB.Create(&appt)

	appointmentHandler := NewAppointmentHandler()

	app := fiber.New()
	app.Post("/appointments/:id/whatsapp-link", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(doctor.ID))
		return appointmentHandler.WhatsAppLink(c)
	})

	req := httptest.NewRequest("POST", fmt.Sprintf("/appointments/%d/whatsapp-link", appt.ID), nil)
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatalf("Request failed: %v", err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("Expected status 200, got %d", resp.StatusCode)
	}

	var result struct {
		Link         string `json:"link"`
		WhatsAppLink string `json:"whatsapp_link"`
		Message      string `json:"message"`
		Phone        string `json:"phone"`
	}
	json.NewDecoder(resp.Body).Decode(&result)

	expectedPhone := "5511999998888"
	if result.Phone != expectedPhone {
		t.Errorf("Expected phone %s, got %s", expectedPhone, result.Phone)
	}

	expectedMessage := "Olá Ana Souza, confirmamos sua consulta na Clínica Odonto Riso dia 15/10/2026 às 14:30?"
	if result.Message != expectedMessage {
		t.Errorf("Expected message '%s', got '%s'", expectedMessage, result.Message)
	}

	if !strings.HasPrefix(result.Link, "https://wa.me/5511999998888?text=") {
		t.Errorf("Expected link to start with 'https://wa.me/5511999998888?text=', got '%s'", result.Link)
	}
}

func TestAppointmentInvalidRequests(t *testing.T) {
	setupPhase2TestDB(t)

	clinic := domain.Clinic{Name: "Clínica Teste Err", CNPJ: "40.000.000/0001-00"}
	database.DB.Create(&clinic)

	appointmentHandler := NewAppointmentHandler()

	app := fiber.New()
	app.Post("/appointments/:id/confirm", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(1))
		return appointmentHandler.Confirm(c)
	})
	app.Post("/appointments/:id/miss", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(1))
		return appointmentHandler.Miss(c)
	})
	app.Post("/appointments/:id/whatsapp-link", func(c *fiber.Ctx) error {
		c.Locals("clinic_id", float64(clinic.ID))
		c.Locals("user_id", float64(1))
		return appointmentHandler.WhatsAppLink(c)
	})

	// 1. Invalid ID parameter (non-numeric)
	reqInv := httptest.NewRequest("POST", "/appointments/invalid_id/confirm", nil)
	respInv, _ := app.Test(reqInv, -1)
	if respInv.StatusCode != 400 {
		t.Errorf("Expected status 400 for invalid ID, got %d", respInv.StatusCode)
	}

	// 2. Non-existent appointment ID
	reqNotFound := httptest.NewRequest("POST", "/appointments/999999/confirm", nil)
	respNotFound, _ := app.Test(reqNotFound, -1)
	if respNotFound.StatusCode != 400 {
		t.Errorf("Expected status 400 for non-existent appointment ID, got %d", respNotFound.StatusCode)
	}

	reqMissNotFound := httptest.NewRequest("POST", "/appointments/999999/miss", nil)
	respMissNotFound, _ := app.Test(reqMissNotFound, -1)
	if respMissNotFound.StatusCode != 400 {
		t.Errorf("Expected status 400 for non-existent appointment ID on miss, got %d", respMissNotFound.StatusCode)
	}

	reqWALinkNotFound := httptest.NewRequest("POST", "/appointments/999999/whatsapp-link", nil)
	respWALinkNotFound, _ := app.Test(reqWALinkNotFound, -1)
	if respWALinkNotFound.StatusCode != 400 {
		t.Errorf("Expected status 400 for non-existent appointment ID on whatsapp-link, got %d", respWALinkNotFound.StatusCode)
	}
}
