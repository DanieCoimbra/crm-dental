package handlers

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"regexp"
	"strconv"

	"github.com/gofiber/fiber/v2"
	"golang.org/x/crypto/bcrypt"
)

type TeamHandler struct {
	userRepo *repositories.UserRepository
}

func NewTeamHandler() *TeamHandler {
	return &TeamHandler{
		userRepo: repositories.NewUserRepository(),
	}
}

type TeamRequest struct {
	Name            string `json:"name"`
	Email           string `json:"email"`
	Password        string `json:"password"`
	Role            string `json:"role"`
	CPF             string `json:"cpf"`
	Phone           string `json:"phone"`
	Address         string `json:"address"`
	MedicalRegistry string `json:"medical_registry"`
	CTPS            string `json:"ctps"`
}

func (h *TeamHandler) Create(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"message": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	var req TeamRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	if match, _ := regexp.MatchString(`^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$`, req.Email); !match {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "E-mail inválido"})
	}

	var role domain.Role
	if err := database.DB.Where("name = ?", req.Role).First(&role).Error; err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Cargo não encontrado"})
	}

	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao gerar senha"})
	}

	user := domain.User{
		Name:            req.Name,
		Email:           req.Email,
		Password:        string(hashedPassword),
		RoleID:          &role.ID,
		CPF:             req.CPF,
		Phone:           req.Phone,
		Address:         req.Address,
		MedicalRegistry: req.MedicalRegistry,
		CTPS:            req.CTPS,
		ClinicID:        clinicID,
	}

	if err := h.userRepo.Create(&user); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao criar funcionário", "error": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(user)
}

func (h *TeamHandler) Update(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"message": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	var req TeamRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	if req.Email != "" {
		if match, _ := regexp.MatchString(`^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$`, req.Email); !match {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "E-mail inválido"})
		}
	}

	user, err := h.userRepo.FindByIDAndClinic(uint(id), clinicID)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": "Funcionário não encontrado nesta clínica"})
	}

	var role domain.Role
	if err := database.DB.Where("name = ?", req.Role).First(&role).Error; err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Cargo não encontrado"})
	}

	user.Name = req.Name
	user.Email = req.Email
	user.RoleID = &role.ID
	user.CPF = req.CPF
	user.Phone = req.Phone
	user.Address = req.Address
	user.MedicalRegistry = req.MedicalRegistry
	user.CTPS = req.CTPS

	if req.Password != "" {
		hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
		if err == nil {
			user.Password = string(hashedPassword)
		}
	}

	if err := h.userRepo.Update(user); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao atualizar funcionário"})
	}

	return c.JSON(user)
}

func (h *TeamHandler) Delete(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"message": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	type DeleteRequest struct {
		Password string `json:"password"`
	}
	var req DeleteRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	authUserID := uint(c.Locals("user_id").(float64))
	authUser, err := h.userRepo.FindByIDAndClinic(authUserID, clinicID)
	if err != nil || bcrypt.CompareHashAndPassword([]byte(authUser.Password), []byte(req.Password)) != nil {
		return c.Status(fiber.StatusUnprocessableEntity).JSON(fiber.Map{"message": "Senha incorreta"})
	}

	if err := h.userRepo.Delete(uint(id), clinicID, authUserID); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao deletar funcionário"})
	}

	return c.SendStatus(fiber.StatusNoContent)
}

func (h *TeamHandler) GetByID(c *fiber.Ctx) error {
	clinicIDVal := c.Locals("clinic_id")
	if clinicIDVal == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"message": "Clínica não identificada"})
	}
	clinicID := uint(clinicIDVal.(float64))

	id, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}
	user, err := h.userRepo.FindByIDAndClinic(uint(id), clinicID)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": "Funcionário não encontrado"})
	}
	return c.JSON(user)
}
