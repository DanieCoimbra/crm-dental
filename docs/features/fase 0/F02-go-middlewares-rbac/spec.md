# Especificação Técnica: Middlewares & Role Security

## 1. Visão Geral Técnica
- **Domínio:** `F02-go-middlewares-rbac`
- **Componentes:** `AuthMiddleware`, `TenantMiddleware`, `RoleGuard`

## 2. Injeção de Contexto em Go Fiber

```go
func TenantMiddleware() fiber.Handler {
    return func(c *fiber.Ctx) error {
        userClaims := c.Locals("user_claims").(*JWTClaims)
        if userClaims.ClinicID == "" {
            return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
                "error": "MISSING_TENANT",
                "message": "Token não contém identificador da clínica",
            })
        }
        c.Locals("clinic_id", userClaims.ClinicID)
        c.Locals("role", userClaims.Role)
        return c.Next()
    }
}
```
