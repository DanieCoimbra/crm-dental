# Especificação Técnica: Profile & Tenant Status

## 1. Visão Geral Técnica
- **Domínio:** `F03-go-profile-tenant`
- **Componentes:** `ProfileHandler`, `ProfileUseCase`

## 2. Fluxo do Handler
```go
func (h *ProfileHandler) GetProfile(c *fiber.Ctx) error {
    userID := c.Locals("user_id").(string)
    clinicID := c.Locals("clinic_id").(string)
    
    profile, err := h.useCase.GetProfile(c.Context(), userID, clinicID)
    if err != nil {
        return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "PROFILE_NOT_FOUND"})
    }
    return c.JSON(profile)
}
```
