package main

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/joho/godotenv"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

type Clinic struct {
	ID   uint
	Name string
}

type User struct {
	ID       uint
	Name     string
	Email    string
	ClinicID uint
}

func main() {
	_ = godotenv.Load()
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		fmt.Println("DATABASE_URL não configurada")
		return
	}

	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		fmt.Printf("Failed to connect to DB: %v\n", err)
		return
	}

	var clinics []Clinic
	db.Table("clinics").Find(&clinics)

	fmt.Println("=== CLINICS ===")
	cb, _ := json.MarshalIndent(clinics, "", "  ")
	fmt.Println(string(cb))

	var users []User
	db.Table("users").Find(&users)

	fmt.Println("=== USERS ===")
	ub, _ := json.MarshalIndent(users, "", "  ")
	fmt.Println(string(ub))
}
