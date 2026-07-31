package handlers

import (
	"fmt"
	"path/filepath"
	"regexp"
	"strings"
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
	ClinicName  string `json:"clinic_name"`
	ClinicEmail string `json:"clinic_email"`
	AdminName   string `json:"admin_name"`
	AdminEmail  string `json:"admin_email"`
	Password    string `json:"password"`
	SessionID   string `json:"session_id,omitempty"`
}

type LoginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type LoginUserResponse struct {
	ID       string `json:"id"`
	ClinicID string `json:"clinic_id"`
	Name     string `json:"name"`
	Email    string `json:"email"`
	Role     string `json:"role"`
}

func (h *AuthHandler) Register(c *fiber.Ctx) error {
	var req RegisterRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	if match, _ := regexp.MatchString(`^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$`, req.AdminEmail); !match {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "E-mail inválido"})
	}

	if len(req.Password) < 8 || !regexp.MustCompile(`[a-zA-Z]`).MatchString(req.Password) || !regexp.MustCompile(`[0-9]`).MatchString(req.Password) {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "A senha deve ter no mínimo 8 caracteres, contendo letras e números"})
	}

	user, clinic, err := h.authService.RegisterClinicOwner(req.ClinicName, req.ClinicEmail, req.AdminName, req.AdminEmail, req.Password, req.SessionID)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
	}

	// Como a rota de register também faz "login" segundo o payload, precisamos gerar o token aqui
	// O token, user, clinic devem ser retornados
	token, _, _, err := h.authService.Login(req.AdminEmail, req.Password)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Erro ao gerar token"})
	}

	return c.Status(fiber.StatusCreated).JSON(fiber.Map{
		"token": token,
		"user": fiber.Map{
			"id":    user.ID,
			"name":  user.Name,
			"email": user.Email,
			"role":  "owner", // Assuming admin/owner is the name in DB, hardcoded owner for simplicity or user.Role.Name if preloaded
		},
		"clinic": fiber.Map{
			"id":            clinic.ID,
			"name":          clinic.Name,
			"trial_ends_at": clinic.TrialEndsAt,
			"status":        clinic.Status,
		},
	})
}

func (h *AuthHandler) Login(c *fiber.Ctx) error {
	var req LoginRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	token, user, lockTime, err := h.authService.Login(req.Email, req.Password)
	if err != nil {
		if lockTime != nil {
			return c.Status(fiber.StatusTooManyRequests).JSON(fiber.Map{
				"error":        err.Error(),
				"locked_until": lockTime,
			})
		}
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": err.Error()})
	}

	h.auditService.LogAction(user.ClinicID, user.ID, "login", "auth", user.ID, c.IP(), c.Get("User-Agent"), "Usuário logou no sistema")

	clinicRepo := h.authService.GetClinicRepository()
	clinic, _ := clinicRepo.FindByID(user.ClinicID)

	roleName := "user"
	if user.Role != nil {
		roleName = user.Role.Name
	}

	return c.JSON(fiber.Map{
		"token": token,
		"user": fiber.Map{
			"id":    user.ID,
			"name":  user.Name,
			"email": user.Email,
			"role":  roleName,
		},
		"clinic": fiber.Map{
			"id":            clinic.ID,
			"name":          clinic.Name,
			"trial_ends_at": clinic.TrialEndsAt,
			"status":        clinic.Status,
		},
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

	clinicRepo := h.authService.GetClinicRepository()
	clinic, _ := clinicRepo.FindByID(user.ClinicID)

	roleName := "user"
	if user.Role != nil {
		roleName = user.Role.Name
	}

	return c.JSON(fiber.Map{
		"user": fiber.Map{
			"id":         user.ID,
			"name":       user.Name,
			"email":      user.Email,
			"role":       roleName,
			"avatar_url": user.Avatar,
			"is_active":  true,
		},
		"clinic": fiber.Map{
			"id":            clinic.ID,
			"name":          clinic.Name,
			"cnpj_cpf":      clinic.CNPJ,
			"phone":         clinic.Phone,
			"email":         clinic.Email,
			"trial_ends_at": clinic.TrialEndsAt,
			"status":        clinic.Status,
		},
	})
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

	ext := strings.ToLower(filepath.Ext(file.Filename))
	validExts := map[string]bool{
		".jpg": true, ".jpeg": true, ".png": true, ".webp": true,
	}
	if !validExts[ext] {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Extensão de arquivo não permitida. Apenas .jpg, .jpeg, .png, .webp são aceitos."})
	}

	fileContent, err := file.Open()
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao ler arquivo do avatar"})
	}
	defer fileContent.Close()

	safeFilename := filepath.Base(file.Filename)
	filename := fmt.Sprintf("avatar_%.0f_%d_%s", c.Locals("user_id").(float64), time.Now().Unix(), safeFilename)

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
