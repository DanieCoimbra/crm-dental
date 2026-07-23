package routes

import (
	"fmt"
	"time"

	"dental-crm-api/internal/adapters/handlers"
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/database"
	"dental-crm-api/internal/middleware"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/cache"
	"github.com/gofiber/fiber/v2/middleware/limiter"
)

func SetupRoutes(app *fiber.App) {
	// Rota de Health Check
	app.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"status": "ok", "message": "Dental CRM API em Golang está rodando!"})
	})

	// Agrupamento da API v1
	api := app.Group("/api")
	v1 := api.Group("/v1")

	// Handlers Lote 1 e 2
	authHandler := handlers.NewAuthHandler()
	patientHandler := handlers.NewPatientHandler()
	roomHandler := handlers.NewRoomHandler()
	settingHandler := handlers.NewSettingHandler()
	waitlistHandler := handlers.NewWaitlistHandler()
	roleHandler := handlers.NewRoleHandler()
	clinicHandler := handlers.NewClinicHandler()
	dashboardHandler := handlers.NewDashboardHandler()
	auditHandler := handlers.NewAuditHandler()

	// Handlers Lote 3
	evolutionHandler := handlers.NewClinicalEvolutionHandler()
	patientFileHandler := handlers.NewPatientFileHandler()
	financialHandler := handlers.NewFinancialHandler()
	inventoryHandler := handlers.NewInventoryHandler()
	saasHandler := handlers.NewSaaSHandler()

	marketingRepo := repositories.NewMarketingRepository(database.DB)
	marketingHandler := handlers.NewMarketingHandler(marketingRepo)

	// Handlers Lote 4
	appointmentTypeHandler := handlers.NewAppointmentTypeHandler()
	appointmentHandler := handlers.NewAppointmentHandler()
	shiftAssignmentHandler := handlers.NewShiftAssignmentHandler()

	// Rotas Públicas
	authGroup := v1.Group("/auth")
	authGroup.Post("/register", authHandler.Register)

	loginLimiter := limiter.New(limiter.Config{
		Max:        5,
		Expiration: 1 * time.Minute,
		KeyGenerator: func(c *fiber.Ctx) string {
			return c.IP()
		},
		LimitReached: func(c *fiber.Ctx) error {
			return c.Status(fiber.StatusTooManyRequests).JSON(fiber.Map{
				"error_code": "RATE_LIMIT_EXCEEDED",
				"message":    "Muitas tentativas de login. Tente novamente em 1 minuto.",
			})
		},
	})
	authGroup.Post("/login", loginLimiter, authHandler.Login)

	// Webhooks (Public)
	webhookHandler := handlers.NewWebhookHandler()
	v1.Post("/webhooks/stripe", webhookHandler.HandleStripe)

	// Rotas Privadas (Exigem Autenticação)
	private := v1.Group("/", middleware.AuthRequired, middleware.RequireActiveSubscription())

	// User Profile
	private.Get("/user", authHandler.Profile)
	private.Put("/profile", authHandler.UpdateProfile)
	private.Post("/profile/avatar", authHandler.UpdateAvatar)
	private.Post("/profile/room", authHandler.UpdateRoom)
	private.Put("/users/preferences", authHandler.UpdatePreferences)

	// Team and Roles
	adminOnly := middleware.RoleRequired("admin", "owner")
	adminOrManager := middleware.RoleRequired("admin", "manager", "owner")

	// Dashboard (Com cache de 5 minutos agrupado pelo id da clínica)
	private.Get("/dashboard/stats", adminOnly, cache.New(cache.Config{
		Expiration: 5 * time.Minute,
		KeyGenerator: func(c *fiber.Ctx) string {
			clinicIDVal := c.Locals("clinic_id")
			if clinicIDVal == nil {
				return "dashboard_anon"
			}
			return fmt.Sprintf("dashboard_v1_%v", clinicIDVal)
		},
	}), dashboardHandler.GetStats)

	private.Get("/users", authHandler.ListUsers)
	private.Get("/roles", roleHandler.List)
	private.Get("/roles/:id", roleHandler.GetByID)
	private.Post("/roles", adminOnly, roleHandler.Create)
	private.Put("/roles/:id", adminOnly, roleHandler.Update)
	private.Delete("/roles/:id", adminOnly, roleHandler.Delete)

	private.Get("/permissions", roleHandler.ListPermissions)
	private.Get("/audit-logs", adminOnly, auditHandler.List)

	// Patients
	private.Get("/patients", patientHandler.List)
	private.Get("/patients/:id", patientHandler.GetByID)
	private.Post("/patients", patientHandler.Create)
	private.Put("/patients/:id", patientHandler.Update)
	private.Put("/patients/:id/emr", patientHandler.UpdateEMR)
	private.Delete("/patients/:id", adminOnly, patientHandler.Delete)
	private.Post("/patients-import", adminOrManager, patientHandler.Import)
	private.Get("/patients-export", adminOrManager, patientHandler.Export)

	// Public but scoped
	v1.Get("/patients-import-template", patientHandler.DownloadTemplate)

	// Rooms
	private.Get("/rooms", roomHandler.List)
	private.Get("/rooms/:id", roomHandler.GetByID)
	private.Post("/rooms", roomHandler.Create)
	private.Put("/rooms/:id", roomHandler.Update)
	private.Delete("/rooms/:id", roomHandler.Delete)

	// Appointment Types
	private.Get("/appointment-types", appointmentTypeHandler.List)
	private.Get("/appointment-types/:id", appointmentTypeHandler.GetByID)
	private.Post("/appointment-types", appointmentTypeHandler.Create)
	private.Put("/appointment-types/:id", appointmentTypeHandler.Update)
	private.Delete("/appointment-types/:id", appointmentTypeHandler.Delete)

	// Settings
	private.Get("/settings", settingHandler.List)
	private.Get("/settings/holidays", settingHandler.Holidays)
	private.Post("/settings", adminOrManager, settingHandler.Save)
	private.Put("/settings", adminOrManager, settingHandler.Save)
	private.Get("/export", adminOrManager, settingHandler.Export)
	private.Post("/import", adminOrManager, settingHandler.Import)

	// Clinics
	private.Get("/clinics/me", clinicHandler.GetMe)
	private.Put("/clinics/:id", clinicHandler.Update)

	// Lixeira (Trash)
	trashHandler := handlers.NewTrashHandler()
	private.Get("/trash", adminOrManager, trashHandler.List)
	private.Get("/trash/status", adminOrManager, trashHandler.Status)
	private.Post("/trash/:type/:id/restore", adminOrManager, trashHandler.Restore)
	private.Delete("/trash/:type/:id/force", adminOnly, trashHandler.ForceDelete)

	// Waitlist
	private.Get("/waitlists", waitlistHandler.List)
	private.Get("/waitlists/:id", waitlistHandler.GetByID)
	private.Post("/waitlists/check-matches", waitlistHandler.CheckMatches)
	private.Post("/waitlists", waitlistHandler.Create)
	private.Put("/waitlists/:id", waitlistHandler.Update)
	private.Delete("/waitlists/:id", waitlistHandler.Delete)

	// Lote 3: Prontuário Médico (Sub-rotas de pacientes)
	// Evoluções Clínicas
	private.Get("/patients/:patient_id/evolutions", evolutionHandler.ListByPatient)
	private.Post("/patients/:patient_id/evolutions", evolutionHandler.Create)
	private.Delete("/evolutions/:id", evolutionHandler.Delete)

	// Arquivos (Uploads/Imagens)
	private.Get("/patients/:patient_id/files", patientFileHandler.ListByPatient)
	private.Post("/patients/:patient_id/files", patientFileHandler.Create)
	private.Delete("/files/:id", patientFileHandler.Delete)

	// Lote 4: Agenda e Agendamentos
	private.Get("/appointments", appointmentHandler.List)
	private.Post("/appointments", appointmentHandler.Create)
	private.Put("/appointments/:id", appointmentHandler.Update)
	private.Post("/appointments/:id/start", appointmentHandler.Start)
	private.Post("/appointments/:id/finish", appointmentHandler.Finish)
	private.Delete("/appointments/:id", appointmentHandler.Delete)

	private.Get("/shift-assignments", shiftAssignmentHandler.List)
	private.Post("/shift-assignments", shiftAssignmentHandler.Create)
	private.Delete("/shift-assignments/:id", shiftAssignmentHandler.Delete)

	// Team / Users CRUD
	teamHandler := handlers.NewTeamHandler()
	private.Get("/team", authHandler.ListUsers)
	private.Get("/team/:id", teamHandler.GetByID)
	private.Post("/team", adminOrManager, teamHandler.Create)
	private.Put("/team/:id", adminOrManager, teamHandler.Update)
	private.Delete("/team/:id", adminOnly, teamHandler.Delete)

	// Lote 5: Financeiro
	private.Get("/financial/transactions", financialHandler.GetTransactions)
	private.Post("/financial/transactions", financialHandler.CreateTransaction)
	private.Put("/financial/installments/:id/pay", financialHandler.PayInstallment)

	// Lote 6: Estoque
	private.Get("/inventory", inventoryHandler.ListItems)
	private.Post("/inventory", inventoryHandler.CreateItem)
	private.Put("/inventory/:id", inventoryHandler.UpdateItem)
	private.Delete("/inventory/:id", inventoryHandler.DeleteItem)
	private.Get("/inventory/:id/transactions", inventoryHandler.GetTransactions)
	private.Post("/inventory/:id/transactions", inventoryHandler.RegisterTransaction)

	// Lote 7: SaaS (Cupons/Afiliados)
	private.Get("/saas/coupons", adminOnly, saasHandler.ListCoupons)
	private.Post("/saas/coupons", adminOnly, saasHandler.CreateCoupon)
	private.Post("/saas/validate-coupon", adminOrManager, saasHandler.ValidateCoupon)
	private.Post("/saas/apply-coupon", adminOrManager, saasHandler.ApplyCoupon)
	private.Post("/saas/change-plan", adminOrManager, saasHandler.ChangePlan)

	// Lote 8: Marketing da Clínica (Cupons e Afiliados para Pacientes)
	private.Get("/marketing/promo-codes", adminOrManager, marketingHandler.GetPromoCodes)
	private.Post("/marketing/promo-codes", adminOrManager, marketingHandler.CreatePromoCode)
	private.Delete("/marketing/promo-codes/:id", adminOrManager, marketingHandler.DeletePromoCode)

	private.Get("/marketing/partners", adminOrManager, marketingHandler.GetPartners)
	private.Post("/marketing/partners", adminOrManager, marketingHandler.CreatePartner)
	private.Delete("/marketing/partners/:id", adminOrManager, marketingHandler.DeletePartner)
}
