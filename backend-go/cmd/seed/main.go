package main

import (
	"log"
	"os"
	"time"

	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/pkg/encryption"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func main() {
	// Initialize Encryption
	os.Setenv("APP_KEY", "base64:pkJmCsrMG8E+avKs/1FWHWGj59/NDsgfPd8+lUPQPPQ=")
	if err := encryption.Init(); err != nil {
		log.Fatalf("Failed to init encryption: %v", err)
	}

	dsn := "postgresql://postgres:HjNat2LJgZMUx6@db.secrmgyfvteesacmifvi.supabase.co:5432/postgres"
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		log.Fatalf("Failed to connect to DB: %v", err)
	}

	// Find User go@clinica.com
	var user domain.User
	if err := db.Where("email = ?", "go@clinica.com").First(&user).Error; err != nil {
		log.Fatalf("User go@clinica.com not found! Error: %v", err)
	}
	log.Printf("Found user %s with ClinicID %d\n", user.Email, user.ClinicID)
	
	clinicID := user.ClinicID

	// Create Dummy Patients linked to this clinic
	patients := []domain.Patient{
		{Name: "João Silva", CPF: "111.111.111-11", Email: "joao@example.com", Phone: "11999999999", ClinicID: clinicID, BirthDate: ptrTime(time.Now().AddDate(-30, 0, 0))},
		{Name: "Maria Oliveira", CPF: "222.222.222-22", Email: "maria@example.com", Phone: "11888888888", ClinicID: clinicID, BirthDate: ptrTime(time.Now().AddDate(-25, 0, 0))},
		{Name: "Carlos Souza", CPF: "333.333.333-33", Email: "carlos@example.com", Phone: "11777777777", ClinicID: clinicID, BirthDate: ptrTime(time.Now().AddDate(-40, 0, 0))},
	}

	for i := range patients {
		var p domain.Patient
		if err := db.Where("cpf = ? AND clinic_id = ?", patients[i].CPF, clinicID).First(&p).Error; err != nil {
			db.Create(&patients[i])
			log.Printf("Created patient %s\n", patients[i].Name)
		} else {
			patients[i] = p
		}
	}

	// Create Dummy Appointments
	now := time.Now()
	appointments := []domain.Appointment{
		{ClinicID: clinicID, DoctorID: user.ID, PatientID: patients[0].ID, StartTime: ptrTime(now.Add(1 * time.Hour)), EndTime: ptrTime(now.Add(2 * time.Hour)), Status: "scheduled"},
		{ClinicID: clinicID, DoctorID: user.ID, PatientID: patients[1].ID, StartTime: ptrTime(now.Add(3 * time.Hour)), EndTime: ptrTime(now.Add(4 * time.Hour)), Status: "scheduled"},
		{ClinicID: clinicID, DoctorID: user.ID, PatientID: patients[2].ID, StartTime: ptrTime(now.Add(-2 * time.Hour)), EndTime: ptrTime(now.Add(-1 * time.Hour)), Status: "completed"},
	}

	for _, apt := range appointments {
		var a domain.Appointment
		if err := db.Where("patient_id = ? AND status = ? AND doctor_id = ?", apt.PatientID, apt.Status, apt.DoctorID).First(&a).Error; err != nil {
			db.Create(&apt)
			log.Printf("Created appointment for patient ID %d\n", apt.PatientID)
		}
	}

	log.Println("Database seeded successfully for go@clinica.com (ClinicID: ", clinicID, ")!")
}

func ptrTime(t time.Time) *time.Time {
	return &t
}
