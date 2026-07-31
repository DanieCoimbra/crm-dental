package middleware

import (
	"strings"

	"github.com/gofiber/fiber/v2"
	"github.com/golang-jwt/jwt/v5"
)

type JWTClaims struct {
	UserID   string `json:"user_id"`
	ClinicID string `json:"clinic_id"`
	Role     string `json:"role"`
	jwt.RegisteredClaims
}

func AuthMiddleware(jwtSecret string) fiber.Handler {
	return func(c *fiber.Ctx) error {
		authHeader := c.Get("Authorization")
		if authHeader == "" {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
				"error":   "UNAUTHORIZED",
				"message": "Token de autenticação ausente ou expirado",
				"details": nil,
			})
		}

		parts := strings.Split(authHeader, " ")
		if len(parts) != 2 || parts[0] != "Bearer" {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
				"error":   "UNAUTHORIZED",
				"message": "Formato de token inválido",
				"details": nil,
			})
		}

		tokenString := parts[1]
		claims := &JWTClaims{}

		token, err := jwt.ParseWithClaims(tokenString, claims, func(token *jwt.Token) (interface{}, error) {
			return []byte(jwtSecret), nil
		})

		if err != nil || !token.Valid {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
				"error":   "UNAUTHORIZED",
				"message": "Token de autenticação ausente ou expirado",
				"details": nil,
			})
		}

		c.Locals("user_id", claims.UserID)
		c.Locals("clinic_id", claims.ClinicID)
		c.Locals("role", claims.Role)

		return c.Next()
	}
}

func RoleGuard(allowedRoles ...string) fiber.Handler {
	return func(c *fiber.Ctx) error {
		userRole, ok := c.Locals("role").(string)
		if !ok || userRole == "" {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
				"error":   "FORBIDDEN",
				"message": "Acesso negado: perfil de usuário não identificado",
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
			"message": "Acesso negado: o perfil '" + userRole + "' não possui permissão para esta rota",
			"details": nil,
		})
	}
}
