package handlers

import (
	"context"
	"time"

	"dental-crm-api/internal/database"

	"github.com/gofiber/fiber/v2"
)

type HealthHandler struct{}

func NewHealthHandler() *HealthHandler {
	return &HealthHandler{}
}

type HealthResponse struct {
	Status    string    `json:"status"`
	Database  string    `json:"database"`
	Timestamp time.Time `json:"timestamp"`
	Version   string    `json:"version"`
	Message   string    `json:"message,omitempty"`
}

func (h *HealthHandler) Check(c *fiber.Ctx) error {
	ctx, cancel := context.WithTimeout(c.Context(), 3*time.Second)
	defer cancel()

	status := "ok"
	dbStatus := "connected"
	httpStatus := fiber.StatusOK
	var errMsg string

	if database.DB != nil {
		sqlDB, err := database.DB.DB()
		if err != nil {
			status = "error"
			dbStatus = "disconnected"
			httpStatus = fiber.StatusServiceUnavailable
			errMsg = "Erro ao obter instância SQL do GORM: " + err.Error()
		} else if err := sqlDB.PingContext(ctx); err != nil {
			status = "error"
			dbStatus = "disconnected"
			httpStatus = fiber.StatusServiceUnavailable
			errMsg = "Erro de conexão com o banco de dados (Supabase Pooler): " + err.Error()
		}
	} else {
		status = "error"
		dbStatus = "disconnected"
		httpStatus = fiber.StatusServiceUnavailable
		errMsg = "Instância de banco de dados (GORM) não inicializada."
	}

	resp := HealthResponse{
		Status:    status,
		Database:  dbStatus,
		Timestamp: time.Now().UTC(),
		Version:   "1.0.0",
		Message:   errMsg,
	}

	return c.Status(httpStatus).JSON(resp)
}
