package handlers

import (
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"

	"dental-clinic-crm/backend-go/internal/core/domain"
	"dental-clinic-crm/backend-go/internal/middleware"
)

type AuthHandler struct {
	db        *gorm.DB
	jwtSecret string
}

func NewAuthHandler(db *gorm.DB, jwtSecret string) *AuthHandler {
	return &AuthHandler{db: db, jwtSecret: jwtSecret}
}

type RegisterClinicPayload struct {
	ClinicName  string `json:"clinic_name"`
	ClinicEmail string `json:"clinic_email"`
	AdminName   string `json:"admin_name"`
	AdminEmail  string `json:"admin_email"`
	Password    string `json:"password"`
}

type LoginPayload struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

func (h *AuthHandler) RegisterClinic(c *fiber.Ctx) error {
	var payload RegisterClinicPayload
	if err := c.BodyParser(&payload); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error":   "BAD_REQUEST",
			"message": "Payload JSON inválido",
		})
	}

	if payload.ClinicName == "" || payload.ClinicEmail == "" || payload.AdminName == "" || payload.AdminEmail == "" || payload.Password == "" {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error":   "VALIDATION_ERROR",
			"message": "Todos os campos obrigatorios devem ser preenchidos",
		})
	}

	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(payload.Password), bcrypt.DefaultCost)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error":   "SERVER_ERROR",
			"message": "Erro ao processar senha",
		})
	}

	tx := h.db.Begin()

	clinic := domain.Clinic{
		ID:          uuid.New(),
		Name:        payload.ClinicName,
		Email:       payload.ClinicEmail,
		TrialEndsAt: time.Now().AddDate(0, 0, 14),
		Status:      "active",
	}
	if err := tx.Create(&clinic).Error; err != nil {
		tx.Rollback()
		return c.Status(fiber.StatusConflict).JSON(fiber.Map{
			"error":   "CLINIC_EXISTS",
			"message": "Clinica com este e-mail ja cadastrada",
		})
	}

	user := domain.User{
		ID:           uuid.New(),
		ClinicID:     clinic.ID,
		Name:         payload.AdminName,
		Email:        payload.AdminEmail,
		PasswordHash: string(hashedPassword),
		Role:         "admin",
		IsActive:     true,
	}
	if err := tx.Create(&user).Error; err != nil {
		tx.Rollback()
		return c.Status(fiber.StatusConflict).JSON(fiber.Map{
			"error":   "USER_EXISTS",
			"message": "Usuario admin com este e-mail ja cadastrado",
		})
	}

	if err := tx.Commit().Error; err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error":   "SERVER_ERROR",
			"message": "Erro ao concluir transacao de cadastro",
		})
	}

	token, err := h.generateJWT(user.ID.String(), clinic.ID.String(), user.Role)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error":   "SERVER_ERROR",
			"message": "Erro ao gerar token de acesso",
		})
	}

	return c.Status(fiber.StatusCreated).JSON(fiber.Map{
		"token": token,
		"user": fiber.Map{
			"id":    user.ID,
			"name":  user.Name,
			"email": user.Email,
			"role":  user.Role,
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
	var payload LoginPayload
	if err := c.BodyParser(&payload); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error":   "BAD_REQUEST",
			"message": "Payload JSON inválido",
		})
	}

	var user domain.User
	if err := h.db.Preload("Clinic").Where("email = ?", payload.Email).First(&user).Error; err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error":   "UNAUTHORIZED",
			"message": "Credenciais invalidas",
		})
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(payload.Password)); err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error":   "UNAUTHORIZED",
			"message": "Credenciais invalidas",
		})
	}

	token, err := h.generateJWT(user.ID.String(), user.ClinicID.String(), user.Role)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error":   "SERVER_ERROR",
			"message": "Erro ao gerar token de acesso",
		})
	}

	return c.JSON(fiber.Map{
		"token": token,
		"user": fiber.Map{
			"id":    user.ID,
			"name":  user.Name,
			"email": user.Email,
			"role":  user.Role,
		},
		"clinic": fiber.Map{
			"id":            user.Clinic.ID,
			"name":          user.Clinic.Name,
			"trial_ends_at": user.Clinic.TrialEndsAt,
			"status":        user.Clinic.Status,
		},
	})
}

func (h *AuthHandler) GetProfile(c *fiber.Ctx) error {
	userID := c.Locals("user_id").(string)

	var user domain.User
	if err := h.db.Preload("Clinic").Where("id = ?", userID).First(&user).Error; err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{
			"error":   "NOT_FOUND",
			"message": "Perfil de usuario nao encontrado",
		})
	}

	return c.JSON(fiber.Map{
		"user": fiber.Map{
			"id":         user.ID,
			"name":       user.Name,
			"email":      user.Email,
			"role":       user.Role,
			"avatar_url": user.AvatarURL,
			"is_active":  user.IsActive,
		},
		"clinic": fiber.Map{
			"id":            user.Clinic.ID,
			"name":          user.Clinic.Name,
			"cnpj_cpf":      user.Clinic.CnpjCpf,
			"phone":         user.Clinic.Phone,
			"email":         user.Clinic.Email,
			"trial_ends_at": user.Clinic.TrialEndsAt,
			"status":        user.Clinic.Status,
		},
	})
}

func (h *AuthHandler) generateJWT(userID, clinicID, role string) (string, error) {
	claims := middleware.JWTClaims{
		UserID:   userID,
		ClinicID: clinicID,
		Role:     role,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(24 * time.Hour)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(h.jwtSecret))
}
