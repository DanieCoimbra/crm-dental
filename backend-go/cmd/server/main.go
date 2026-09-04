package main

import (
	"log"
	"os"
	"strings"

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

	// Validação de segurança para JWT_SECRET em repositório público / produção
	jwtSecret := os.Getenv("JWT_SECRET")
	if jwtSecret == "" {
		log.Println("⚠️ AVISO DE SEGURANÇA: JWT_SECRET não configurado! Usando chave padrão para desenvolvimento local.")
		os.Setenv("JWT_SECRET", "dental_crm_super_secret_portfolio_key_2026_change_in_prod")
	}

	// Conecta ao Supabase (PostgreSQL) via GORM
	database.Connect()

	// Inicia a criptografia AES (Compatível com Laravel)
	if err := encryption.Init(); err != nil {
		log.Printf("Aviso: erro ao iniciar módulo de criptografia: %v", err)
	}

	// Inicia o Fiber
	app := fiber.New(fiber.Config{
		AppName:   "Dental CRM API (Golang)",
		BodyLimit: 50 * 1024 * 1024, // Limite de 50MB para suportar Raio-X pesados
	})

	// Middlewares globais
	app.Use(logger.New())

	allowedOrigins := os.Getenv("ALLOWED_ORIGINS")

	app.Use(cors.New(cors.Config{
		AllowOriginsFunc: func(origin string) bool {
			if allowedOrigins == "*" {
				return true
			}
			// Origem padrão do frontend em produção (Vercel) e desenvolvimento local
			if origin == "https://crm-clinica-ten.vercel.app" || strings.HasPrefix(origin, "http://localhost:") || strings.HasPrefix(origin, "http://127.0.0.1:") {
				return true
			}
			if allowedOrigins != "" {
				for _, allowed := range strings.Split(allowedOrigins, ",") {
					if strings.TrimSpace(allowed) == origin {
						return true
					}
				}
			}
			return false
		},
		AllowCredentials: true,
		AllowHeaders:     "Origin, Content-Type, Accept, Authorization, X-Requested-With",
		AllowMethods:     "GET, POST, PUT, DELETE, OPTIONS, PATCH",
		MaxAge:           86400,
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
