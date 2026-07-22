package middleware

import (
	"github.com/gofiber/fiber/v2"
)

// RoleRequired restringe o acesso às rotas com base no papel (role) do usuário logado
func RoleRequired(allowedRoles ...string) fiber.Handler {
	return func(c *fiber.Ctx) error {
		userRole, ok := c.Locals("role").(string)
		if !ok || userRole == "" {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"message": "Acesso negado. Cargo não identificado."})
		}

		for _, role := range allowedRoles {
			if userRole == role {
				return c.Next()
			}
		}

		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"message": "Você não tem permissão para realizar esta ação."})
	}
}
