package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"

	"github.com/gofiber/fiber/v2"
)

type RoleHandler struct {
	roleService *services.RoleService
}

func NewRoleHandler() *RoleHandler {
	return &RoleHandler{
		roleService: services.NewRoleService(),
	}
}

type RoleRequest struct {
	Name        string   `json:"name"`
	Permissions []string `json:"permissions"`
}

func (h *RoleHandler) ListPermissions(c *fiber.Ctx) error {
	permissions := h.roleService.GetAvailablePermissions()
	return c.JSON(permissions)
}

func (h *RoleHandler) List(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	roles, err := h.roleService.ListRoles(clinicID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar cargos"})
	}

	return c.JSON(roles)
}

func (h *RoleHandler) Create(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req RoleRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	role, err := h.roleService.CreateRole(clinicID, req.Name, req.Permissions)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(role)
}

func (h *RoleHandler) Update(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	var req RoleRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	role, err := h.roleService.UpdateRole(clinicID, uint(id), req.Name, req.Permissions)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(role)
}

func (h *RoleHandler) Delete(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	id, err := strconv.ParseUint(c.Params("id"), 10, 32)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	if err := h.roleService.DeleteRole(clinicID, uint(id)); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.SendStatus(fiber.StatusNoContent)
}

func (h *RoleHandler) GetByID(c *fiber.Ctx) error {
	id, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}
	role, err := h.roleService.GetRole(uint(id))
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}
	return c.JSON(role)
}
