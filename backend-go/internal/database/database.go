package database

import (
	"log"
	"os"

	"dental-crm-api/internal/core/domain"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

var DB *gorm.DB

func Connect() {
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		log.Fatal("DATABASE_URL não configurada no .env")
	}

	db, err := gorm.Open(postgres.New(postgres.Config{
		DSN:                  dsn,
		PreferSimpleProtocol: true, // Desabilita prepared statements para compatibilidade com o Pooler do Supabase
	}), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Info), // Mostra as queries no console
	})
	sqlDB, err := db.DB()
	if err != nil {
		log.Fatal("❌ Falha ao obter SQL DB do GORM: ", err)
	}

	if err := sqlDB.Ping(); err != nil {
		log.Fatal("❌ Falha ao realizar ping no banco de dados Supabase: ", err)
	}

	log.Println("✅ Conectado com sucesso ao Supabase via GORM!")
	DB = db

	RunMigrations()
}

func RunMigrations() {
	log.Println("Rodando AutoMigrate...")

	// Limpar tabelas legado que continham UUID ao invés de Uint para evitar erros de cast
	DB.Migrator().DropTable(&domain.ReferralPartner{})
	DB.Migrator().DropTable(&domain.PromoCode{})

	// O GORM criará as tabelas/colunas faltantes no Supabase automaticamente
	err := DB.AutoMigrate(
		&domain.Clinic{},
		&domain.Role{},
		&domain.User{},
		&domain.Patient{},
		&domain.Room{},
		&domain.Setting{},
		&domain.AppointmentType{},
		&domain.Waitlist{},
		&domain.ClinicalEvolution{},
		&domain.PatientFile{},
		&domain.AuditLog{},
		&domain.Appointment{},
		&domain.ShiftAssignment{},
		&domain.ClinicTransaction{},
		&domain.ClinicInstallment{},
		&domain.InventoryItem{},
		&domain.InventoryTransaction{},
		&domain.ProcedureMaterial{},
		&domain.Affiliate{},
		&domain.Coupon{},
		&domain.ReferralPartner{},
		&domain.PromoCode{},
		&domain.Subscription{},
	)
	if err != nil {
		log.Fatal("❌ Erro ao rodar migrations: ", err)
	}
	log.Println("✅ AutoMigrate concluído!")

	SeedRoles()
}

func SeedRoles() {
	roles := []domain.Role{
		{Name: "owner", Permissions: `["manage_team", "manage_settings", "manage_billing", "view_reports", "manage_appointments"]`},
		{Name: "manager", Permissions: `["manage_team", "manage_settings", "view_reports", "manage_appointments"]`},
		{Name: "doctor", Permissions: `["manage_medical_records", "view_appointments"]`},
		{Name: "receptionist", Permissions: `["manage_appointments", "view_patients", "manage_waitlist"]`},
	}

	for _, role := range roles {
		var existingRole domain.Role
		if err := DB.Where("name = ?", role.Name).First(&existingRole).Error; err != nil {
			// Role não existe, criar
			DB.Create(&role)
			log.Printf("✅ Cargo %s criado com sucesso", role.Name)
		} else {
			// Atualizar permissões caso existam
			existingRole.Permissions = role.Permissions
			DB.Save(&existingRole)
		}
	}
}
