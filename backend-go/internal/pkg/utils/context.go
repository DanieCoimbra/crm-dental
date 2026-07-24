package utils

import "github.com/gofiber/fiber/v2"

// GetClinicID extrai e converte com segurança o ID da clínica injetado pelo middleware AuthRequired no contexto do Fiber
func GetClinicID(c *fiber.Ctx) (uint, bool) {
	val := c.Locals("clinic_id")
	if val == nil {
		return 0, false
	}
	switch v := val.(type) {
	case float64:
		return uint(v), true
	case uint:
		return v, true
	case int:
		return uint(v), true
	default:
		return 0, false
	}
}

// GetUserID extrai e converte com segurança o ID do usuário autenticado no contexto do Fiber
func GetUserID(c *fiber.Ctx) (uint, bool) {
	val := c.Locals("user_id")
	if val == nil {
		return 0, false
	}
	switch v := val.(type) {
	case float64:
		return uint(v), true
	case uint:
		return v, true
	case int:
		return uint(v), true
	default:
		return 0, false
	}
}
