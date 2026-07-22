package main

import (
	"log"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func main() {
	dsn := "postgresql://postgres:HjNat2LJgZMUx6@db.secrmgyfvteesacmifvi.supabase.co:5432/postgres"
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		log.Fatalf("Failed to connect to DB: %v", err)
	}

	clinicIDToRemove := 2

	log.Printf("Starting cleanup for ClinicID: %d...\n", clinicIDToRemove)

	// Hard delete appointments
	res := db.Exec("DELETE FROM appointments WHERE clinic_id = ?", clinicIDToRemove)
	log.Printf("Deleted %d appointments.", res.RowsAffected)

	// Hard delete patients
	res = db.Exec("DELETE FROM patients WHERE clinic_id = ?", clinicIDToRemove)
	log.Printf("Deleted %d patients.", res.RowsAffected)

	// Hard delete clinic
	res = db.Exec("DELETE FROM clinics WHERE id = ?", clinicIDToRemove)
	log.Printf("Deleted %d clinics.", res.RowsAffected)

	log.Println("Cleanup completed successfully!")
}
