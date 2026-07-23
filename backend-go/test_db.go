package main

import (
	"fmt"
	"os"

	"github.com/joho/godotenv"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func main() {
	_ = godotenv.Load()
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		fmt.Println("DATABASE_URL não definida")
		return
	}

	db, _ := gorm.Open(postgres.Open(dsn), &gorm.Config{})

	var count int64
	db.Table("users").Count(&count)
	fmt.Printf("Total users in DB: %d\n", count)

	var roleID *uint
	db.Table("users").Where("email = ?", "go@clinica.com").Select("role_id").Row().Scan(&roleID)
	if roleID != nil {
		fmt.Printf("Role ID: %d\n", *roleID)
	} else {
		fmt.Printf("Role ID is NULL!\n")
	}
}
