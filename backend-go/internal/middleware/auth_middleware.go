package middleware

import (
	"fmt"
	"os"
	"strings"

	"github.com/gofiber/fiber/v2"
	"github.com/golang-jwt/jwt/v5"
)

// AuthRequired intercepta a requisição, verifica o JWT e injeta os dados do usuário no contexto
func AuthRequired(c *fiber.Ctx) error {
	return RequireAuth(c)
}

// RequireAuth valida o JWT e injeta clinic_id, user_id e role no contexto do Fiber
func RequireAuth(c *fiber.Ctx) error {
	authHeader := c.Get("Authorization")
	tokenString := ""

	if authHeader != "" && strings.HasPrefix(authHeader, "Bearer ") {
		tokenString = strings.TrimPrefix(authHeader, "Bearer ")
	} else {
		tokenString = c.Query("token")
	}

	if tokenString == "" {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Token não fornecido ou inválido"})
	}

	token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("método de assinatura inesperado: %v", token.Header["alg"])
		}
		return []byte(os.Getenv("JWT_SECRET")), nil
	})

	if err != nil || !token.Valid {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Token inválido ou expirado"})
	}

	claims, ok := token.Claims.(jwt.MapClaims)
	if !ok {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Falha ao extrair claims do token"})
	}

	// Injetar dados cruciais para o Multi-Tenancy
	c.Locals("user_id", claims["sub"])
	c.Locals("clinic_id", claims["clinic_id"])
	c.Locals("role", claims["role"])

	return c.Next()
}

// RequireRole é o middleware de RBAC que bloqueia acessos se a role do usuário não for permitida
func RequireRole(allowedRoles ...string) fiber.Handler {
	return func(c *fiber.Ctx) error {
		userRole, ok := c.Locals("role").(string)
		if !ok || userRole == "" {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"error": "Acesso negado: permissão insuficiente"})
		}

		roleUpper := strings.ToUpper(userRole)
		for _, role := range allowedRoles {
			if strings.ToUpper(role) == roleUpper {
				return c.Next()
			}
		}

		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"error": "Acesso negado: permissão insuficiente"})
	}
}
