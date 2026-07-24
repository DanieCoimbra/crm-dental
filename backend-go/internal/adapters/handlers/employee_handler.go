package handlers

import (
	"regexp"
	"strconv"
	"strings"
	"time"

	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"dental-crm-api/internal/pkg/utils"

	"github.com/gofiber/fiber/v2"
	"golang.org/x/crypto/bcrypt"
)

type EmployeeHandler struct {
	userRepo *repositories.UserRepository
}

func NewEmployeeHandler() *EmployeeHandler {
	return &EmployeeHandler{
		userRepo: repositories.NewUserRepository(),
	}
}

type CreateEmployeeRequest struct {
	Name     string `json:"name"`
	Email    string `json:"email"`
	Password string `json:"password"`
	Role     string `json:"role"`
}

type EmployeeResponse struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Email     string    `json:"email"`
	Role      string    `json:"role"`
	CreatedAt time.Time `json:"created_at"`
}

func (h *EmployeeHandler) Create(c *fiber.Ctx) error {
	clinicID, ok := utils.GetClinicID(c)
	if !ok {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não identificada"})
	}

	var req CreateEmployeeRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	if req.Name == "" || req.Email == "" || req.Password == "" || req.Role == "" {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Todos os campos são obrigatórios"})
	}

	if match, _ := regexp.MatchString(`^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$`, req.Email); !match {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "E-mail inválido"})
	}

	if len(req.Password) < 8 {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "A senha deve ter no mínimo 8 caracteres"})
	}

	cleanEmail := strings.ToLower(strings.TrimSpace(req.Email))

	if strings.ToUpper(req.Role) == "OWNER" {
		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"error": "Não é permitido criar usuários com a função OWNER por este endpoint"})
	}

	// Verificar se email já existe
	if _, err := h.userRepo.FindByEmail(cleanEmail); err == nil {
		return c.Status(fiber.StatusConflict).JSON(fiber.Map{"error": "E-mail já utilizado"})
	}

	// Buscar role pelo nome
	var role domain.Role
	if err := database.DB.Where("LOWER(name) = LOWER(?)", req.Role).First(&role).Error; err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Cargo inválido"})
	}

	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Erro ao processar senha"})
	}

	user := domain.User{
		Name:     strings.TrimSpace(req.Name),
		Email:    cleanEmail,
		Password: string(hashedPassword),
		RoleID:   &role.ID,
		ClinicID: clinicID,
	}

	if err := h.userRepo.Create(&user); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Erro ao criar funcionário"})
	}

	return c.Status(fiber.StatusCreated).JSON(EmployeeResponse{
		ID:        strconv.FormatUint(uint64(user.ID), 10),
		Name:      user.Name,
		Email:     user.Email,
		Role:      role.Name,
		CreatedAt: user.CreatedAt,
	})
}

func (h *EmployeeHandler) List(c *fiber.Ctx) error {
	clinicID, ok := utils.GetClinicID(c)
	if !ok {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Clínica não identificada"})
	}

	users, err := h.userRepo.ListByRole(clinicID, "")
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Erro ao buscar funcionários"})
	}

	employees := make([]EmployeeResponse, 0, len(users))
	for _, u := range users {
		roleName := ""
		if u.Role != nil {
			roleName = u.Role.Name
		}
		employees = append(employees, EmployeeResponse{
			ID:        strconv.FormatUint(uint64(u.ID), 10),
			Name:      u.Name,
			Email:     u.Email,
			Role:      roleName,
			CreatedAt: u.CreatedAt,
		})
	}

	return c.JSON(employees)
}
