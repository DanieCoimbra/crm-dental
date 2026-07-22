package main

import (
	"log"
	"os"

	"dental-crm-api/internal/adapters/routes"
	"dental-crm-api/internal/database"
	"dental-crm-api/internal/pkg/encryption"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/cors"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/joho/godotenv"
)

func main() {
	// Carrega as variáveis do .env (se existir)
	err := godotenv.Load()
	if err != nil {
		log.Println("Aviso: arquivo .env não encontrado. Usando variáveis de ambiente do sistema.")
	}

	// Conecta ao Supabase (PostgreSQL) via GORM
	database.Connect()

	// Inicia a criptografia AES (Compatível com Laravel)
	if err := encryption.Init(); err != nil {
		log.Printf("Aviso: erro ao iniciar módulo de criptografia: %v", err)
	}

	// Inicia o Fiber
	app := fiber.New(fiber.Config{
		AppName: "Dental CRM API (Golang)",
	})

	// Middlewares globais
	app.Use(logger.New())
	app.Use(cors.New(cors.Config{
		AllowOrigins: "*", // Permite acesso de qualquer porta do Flutter Web
		AllowHeaders: "Origin, Content-Type, Accept, Authorization",
	}))

	// Registra as rotas
	routes.SetupRoutes(app)

	// Garante que o diretório de uploads exista
	os.MkdirAll("./uploads", 0755)

	// Serve arquivos estáticos
	app.Static("/uploads", "./uploads")

	// Inicia o servidor na porta definida
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	log.Printf("🚀 Servidor rodando na porta %s", port)
	log.Fatal(app.Listen(":" + port))
}
