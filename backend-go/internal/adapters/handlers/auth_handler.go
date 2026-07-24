package handlers

import (
	"fmt"
	"regexp"
	"time"

	"dental-crm-api/internal/core/services"
	"dental-crm-api/internal/pkg/storage"

	"github.com/gofiber/fiber/v2"
)

type AuthHandler struct {
	authService  *services.AuthService
	auditService *services.AuditService
}

func NewAuthHandler() *AuthHandler {
	return &AuthHandler{
		authService:  services.NewAuthService(),
		auditService: services.NewAuditService(),
	}
}

// DTOs para Request e Response
type RegisterRequest struct {
	ClinicName string `json:"clinic_name"`
	ClinicCNPJ string `json:"clinic_cnpj"`
	Name       string `json:"name"`
	Email      string `json:"email"`
	Password   string `json:"password"`
}

type LoginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

func (h *AuthHandler) Register(c *fiber.Ctx) error {
	var req RegisterRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	if match, _ := regexp.MatchString(`^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$`, req.Email); !match {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "E-mail inválido"})
	}

	user, err := h.authService.RegisterClinicOwner(req.ClinicName, req.ClinicCNPJ, req.Email, req.Name, req.Password)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	// Faz login automático após registro
	token, _, err := h.authService.Login(req.Email, req.Password)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao gerar token"})
	}

	return c.Status(fiber.StatusCreated).JSON(fiber.Map{
		"message": "Cadastro realizado com sucesso",
		"token":   token,
		"user":    user,
	})
}

func (h *AuthHandler) Login(c *fiber.Ctx) error {
	var req LoginRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error_code": "INVALID_REQUEST", "message": "Dados inválidos"})
	}

	token, user, err := h.authService.Login(req.Email, req.Password)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error_code": "INVALID_CREDENTIALS", "message": err.Error()})
	}

	h.auditService.LogAction(user.ClinicID, user.ID, "login", "auth", user.ID, c.IP(), c.Get("User-Agent"), "Usuário logou no sistema")

	return c.JSON(fiber.Map{
		"token": token,
		"user":  user,
	})
}

// Profile retorna os dados do usuário autenticado (através do token)
func (h *AuthHandler) Profile(c *fiber.Ctx) error {
	// Pega o ID do usuário injetado pelo middleware AuthRequired
	userID := c.Locals("user_id").(float64)

	// Podemos usar o userRepo diretamente para buscar os dados frescos
	userRepo := h.authService.GetUserRepository()
	user, err := userRepo.FindByID(uint(userID))
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": "Usuário não encontrado"})
	}

	return c.JSON(user)
}

// ListUsers retorna a lista de usuários da clínica autenticada, opcionalmente filtrando por role
func (h *AuthHandler) ListUsers(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"message": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	roleQuery := c.Query("role")
	userRepo := h.authService.GetUserRepository()

	users, err := userRepo.ListByRole(clinicID, roleQuery)
	if err != nil {
		return c.JSON([]map[string]interface{}{})
	}

	return c.JSON(users)
}

type UpdateProfileRequest struct {
	Name     string `json:"name"`
	Email    string `json:"email"`
	Password string `json:"password"` // Opcional
}

func (h *AuthHandler) UpdateProfile(c *fiber.Ctx) error {
	userID := uint(c.Locals("user_id").(float64))

	var req UpdateProfileRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	if req.Email != "" {
		if match, _ := regexp.MatchString(`^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$`, req.Email); !match {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "E-mail inválido"})
		}
	}

	user, err := h.authService.UpdateProfile(userID, req.Name, req.Email, req.Password)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(user)
}

func (h *AuthHandler) UpdateAvatar(c *fiber.Ctx) error {
	userID := uint(c.Locals("user_id").(float64))

	file, err := c.FormFile("avatar")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Arquivo não encontrado"})
	}

	fileContent, err := file.Open()
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao ler arquivo do avatar"})
	}
	defer fileContent.Close()

	filename := fmt.Sprintf("avatar_%.0f_%d_%s", c.Locals("user_id").(float64), time.Now().Unix(), file.Filename)

	avatarUrl, err := storage.UploadToSupabase("avatars", filename, fileContent, file.Size, file.Header.Get("Content-Type"))
	if err != nil {
		// Fallback para disco local caso as variáveis do Supabase não estejam configuradas em dev
		savePath := "./uploads/" + filename
		if saveErr := c.SaveFile(file, savePath); saveErr != nil {
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao salvar arquivo de avatar"})
		}
		avatarUrl = "/uploads/" + filename
	}

	user, err := h.authService.UpdateAvatar(userID, avatarUrl)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(user)
}

func (h *AuthHandler) UpdateRoom(c *fiber.Ctx) error {
	userID := uint(c.Locals("user_id").(float64))

	type RoomRequest struct {
		RoomID *uint `json:"room_id"`
	}
	var req RoomRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	user, err := h.authService.UpdateRoom(userID, req.RoomID)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(user)
}

type UpdatePreferencesRequest struct {
	ThemePreference string `json:"theme_preference"`
}

func (h *AuthHandler) UpdatePreferences(c *fiber.Ctx) error {
	userID := uint(c.Locals("user_id").(float64))

	var req UpdatePreferencesRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	user, err := h.authService.UpdatePreferences(userID, req.ThemePreference)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(user)
}
