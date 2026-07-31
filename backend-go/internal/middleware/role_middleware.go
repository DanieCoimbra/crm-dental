package middleware

import (
	"github.com/gofiber/fiber/v2"
)

// RoleRequired restringe o acesso às rotas com base no papel (role) do usuário logado
func RoleRequired(allowedRoles ...string) fiber.Handler {
	return func(c *fiber.Ctx) error {
		userRole, ok := c.Locals("role").(string)
		if !ok || userRole == "" {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
				"error":   "FORBIDDEN",
				"message": "Acesso negado: o perfil não possui permissão para esta rota",
				"details": nil,
			})
		}

		for _, role := range allowedRoles {
			if userRole == role {
				return c.Next()
			}
		}

		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
			"error":   "FORBIDDEN",
			"message": "Acesso negado: permissão insuficiente para esta rota",
			"details": nil,
		})
	}
}
