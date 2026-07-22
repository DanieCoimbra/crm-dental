package main

import (
	"fmt"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func main() {
	dsn := "postgresql://postgres:HjNat2LJgZMUx6@db.secrmgyfvteesacmifvi.supabase.co:5432/postgres"
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
