package handlers

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"strconv"

	"github.com/gofiber/fiber/v2"
	"golang.org/x/crypto/bcrypt"
)

type TrashHandler struct{}

func NewTrashHandler() *TrashHandler {
	return &TrashHandler{}
}

func (h *TrashHandler) List(c *fiber.Ctx) error {
	var users []domain.User
	var patients []domain.Patient
	var appointments []domain.Appointment

	// GORM Unscoped() para buscar registros com deleted_at IS NOT NULL
	database.DB.Unscoped().Preload("Role").Where("deleted_at IS NOT NULL").Find(&users)
	database.DB.Unscoped().Where("deleted_at IS NOT NULL").Find(&patients)
	database.DB.Unscoped().Preload("Patient").Preload("Doctor").Where("deleted_at IS NOT NULL").Find(&appointments)

	return c.JSON(fiber.Map{
		"users":        users,
		"patients":     patients,
		"appointments": appointments,
	})
}

func (h *TrashHandler) Restore(c *fiber.Ctx) error {
	itemType := c.Params("type")
	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	switch itemType {
	case "user":
		database.DB.Unscoped().Model(&domain.User{}).Where("id = ?", id).Update("deleted_at", nil)
	case "patient":
		database.DB.Unscoped().Model(&domain.Patient{}).Where("id = ?", id).Update("deleted_at", nil)
	case "appointment":
		database.DB.Unscoped().Model(&domain.Appointment{}).Where("id = ?", id).Update("deleted_at", nil)
	default:
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Tipo de registro inválido"})
	}

	return c.SendStatus(fiber.StatusOK)
}

func (h *TrashHandler) ForceDelete(c *fiber.Ctx) error {
	itemType := c.Params("type")
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

	authUserID := c.Locals("user_id").(float64)
	var authUser domain.User
	if err := database.DB.First(&authUser, uint(authUserID)).Error; err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"message": "Usuário não encontrado"})
	}

	if err := bcrypt.CompareHashAndPassword([]byte(authUser.Password), []byte(req.Password)); err != nil {
		return c.Status(fiber.StatusUnprocessableEntity).JSON(fiber.Map{"message": "Senha incorreta"})
	}

	switch itemType {
	case "user":
		database.DB.Unscoped().Delete(&domain.User{}, id)
	case "patient":
		database.DB.Unscoped().Delete(&domain.Patient{}, id)
	case "appointment":
		database.DB.Unscoped().Delete(&domain.Appointment{}, id)
	default:
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Tipo de registro inválido"})
	}

	return c.SendStatus(fiber.StatusNoContent)
}

func (h *TrashHandler) Status(c *fiber.Ctx) error {
	var userCount, patientCount, apptCount int64
	database.DB.Unscoped().Model(&domain.User{}).Where("deleted_at IS NOT NULL").Count(&userCount)
	database.DB.Unscoped().Model(&domain.Patient{}).Where("deleted_at IS NOT NULL").Count(&patientCount)
	database.DB.Unscoped().Model(&domain.Appointment{}).Where("deleted_at IS NOT NULL").Count(&apptCount)

	total := userCount + patientCount + apptCount
	limit := int64(100)

	return c.JSON(fiber.Map{
		"is_full":        total >= limit,
		"is_almost_full": total >= (limit - 10),
		"total":          total,
		"limit":          limit,
	})
}
