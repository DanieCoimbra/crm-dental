package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"

	"github.com/gofiber/fiber/v2"
)

type RoomHandler struct {
	roomService *services.RoomService
}

func NewRoomHandler() *RoomHandler {
	return &RoomHandler{
		roomService: services.NewRoomService(),
	}
}

type CreateRoomRequest struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	IsActive    bool   `json:"is_active"`
}

func (h *RoomHandler) List(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	rooms, err := h.roomService.ListRooms(clinicID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar salas"})
	}

	return c.JSON(rooms)
}

func (h *RoomHandler) Create(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req CreateRoomRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	room, err := h.roomService.CreateRoom(clinicID, req.Name, req.Description, req.IsActive)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(room)
}

func (h *RoomHandler) Delete(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	deletedBy := uint(c.Locals("user_id").(float64))

	roomID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	err = h.roomService.DeleteRoom(clinicID, uint(roomID), deletedBy)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.SendStatus(fiber.StatusNoContent)
}

func (h *RoomHandler) Update(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	roomID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	var req CreateRoomRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	room, err := h.roomService.UpdateRoom(clinicID, uint(roomID), req.Name, req.Description, req.IsActive)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(room)
}

func (h *RoomHandler) GetByID(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	roomID, err := strconv.Atoi(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "ID inválido"})
	}

	room, err := h.roomService.GetRoom(clinicID, uint(roomID))
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"message": err.Error()})
	}

	return c.JSON(room)
}
