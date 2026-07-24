package database

import (
	"log"
	"os"
	"strings"
	"time"

	"dental-crm-api/internal/core/domain"

	"golang.org/x/crypto/bcrypt"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

var DB *gorm.DB

func maskDSN(dsn string) string {
	if len(dsn) < 10 {
		return "***"
	}
	if idx := strings.Index(dsn, "@"); idx != -1 {
		if protoIdx := strings.Index(dsn, "://"); protoIdx != -1 {
			return dsn[:protoIdx+3] + "***" + dsn[idx:]
		}
	}
	return dsn
}

func cleanDSN(dsn string) string {
	dsn = strings.TrimSpace(dsn)
	dsn = strings.Trim(dsn, "\"'\r\n\t`")
	dsn = strings.ReplaceAll(dsn, "\r", "")
	dsn = strings.ReplaceAll(dsn, "\n", "")
	return dsn
}

func Connect() {
	dsn := os.Getenv("DATABASE_URL")
	dsn = cleanDSN(dsn)
	if dsn == "" {
		log.Fatal("❌ DATABASE_URL não está configurada nas variáveis de ambiente.")
	}

	log.Printf("🔌 Conectando ao banco de dados: %s", maskDSN(dsn))

	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Info), // Mostra as queries no console
	})
	if err != nil {
		log.Fatalf("❌ Falha ao conectar ao banco de dados Supabase: %v", err)
	}
	if db == nil {
		log.Fatal("❌ Instância de banco de dados retornou nil")
	}

	sqlDB, err := db.DB()
	if err != nil {
		log.Fatalf("❌ Falha ao obter SQL DB do GORM: %v", err)
	}

	if err := sqlDB.Ping(); err != nil {
		log.Fatalf("❌ Falha ao realizar ping no banco de dados Supabase: %v", err)
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
		&domain.UsedCheckoutSession{},
	)
	if err != nil {
		log.Fatal("❌ Erro ao rodar migrations: ", err)
	}
	log.Println("✅ AutoMigrate concluído!")

	SeedRoles()
	SeedDefaultUser()
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

func SeedDefaultUser() {
	var count int64
	DB.Model(&domain.User{}).Count(&count)
	if count == 0 {
		var ownerRole domain.Role
		if err := DB.Where("name = ?", "owner").First(&ownerRole).Error; err != nil {
			log.Println("Aviso: cargo owner não encontrado para seed de usuário padrão")
			return
		}

		trialEndsAt := time.Now().Add(365 * 24 * time.Hour)
		clinic := domain.Clinic{
			Name:        "Clínica Odontológica Demo",
			CNPJ:        "00.000.000/0001-00",
			Email:       "admin@clinica.com",
			Status:      "active",
			TrialEndsAt: &trialEndsAt,
		}
		if err := DB.Create(&clinic).Error; err != nil {
			log.Printf("Aviso: erro ao criar clínica demo: %v", err)
			return
		}

		hashedPassword, err := bcrypt.GenerateFromPassword([]byte("123456"), bcrypt.DefaultCost)
		if err != nil {
			log.Printf("Aviso: erro ao gerar hash de senha para usuário padrão: %v", err)
			return
		}

		user := domain.User{
			Name:     "Administrador Demo",
			Email:    "admin@clinica.com",
			Password: string(hashedPassword),
			ClinicID: clinic.ID,
			RoleID:   &ownerRole.ID,
		}
		if err := DB.Create(&user).Error; err != nil {
			log.Printf("Aviso: erro ao criar usuário padrão: %v", err)
		} else {
			log.Println("✅ Usuário padrão criado: admin@clinica.com / 123456")
		}
	}
}
