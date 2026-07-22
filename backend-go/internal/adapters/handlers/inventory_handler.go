package handlers

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"time"

	"github.com/gofiber/fiber/v2"
)

type InventoryHandler struct{}

func NewInventoryHandler() *InventoryHandler {
	return &InventoryHandler{}
}

func (h *InventoryHandler) ListItems(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var items []domain.InventoryItem
	if err := database.DB.Where("clinic_id = ?", clinicID).Find(&items).Error; err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao listar itens de estoque"})
	}

	// Format response to include is_low_stock
	type ItemResponse struct {
		domain.InventoryItem
		IsLowStock bool `json:"is_low_stock"`
	}

	response := []ItemResponse{}
	for _, item := range items {
		response = append(response, ItemResponse{
			InventoryItem: item,
			IsLowStock:    item.Quantity <= item.MinQuantity,
		})
	}

	return c.JSON(response)
}

func (h *InventoryHandler) CreateItem(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req domain.InventoryItem
	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	req.ClinicID = clinicID

	if err := database.DB.Create(&req).Error; err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao criar item no estoque"})
	}

	return c.Status(201).JSON(req)
}

func (h *InventoryHandler) UpdateItem(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	itemID := c.Params("id")

	var item domain.InventoryItem
	if err := database.DB.Where("id = ? AND clinic_id = ?", itemID, clinicID).First(&item).Error; err != nil {
		return c.Status(404).JSON(fiber.Map{"error": "Item não encontrado"})
	}

	var req domain.InventoryItem
	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	item.Name = req.Name
	item.SKU = req.SKU
	item.MinQuantity = req.MinQuantity
	item.Unit = req.Unit

	if err := database.DB.Save(&item).Error; err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao atualizar item"})
	}

	return c.JSON(item)
}

func (h *InventoryHandler) DeleteItem(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	itemID := c.Params("id")

	var item domain.InventoryItem
	if err := database.DB.Where("id = ? AND clinic_id = ?", itemID, clinicID).First(&item).Error; err != nil {
		return c.Status(404).JSON(fiber.Map{"error": "Item não encontrado"})
	}

	if err := database.DB.Delete(&item).Error; err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao deletar item"})
	}

	return c.SendStatus(204)
}

func (h *InventoryHandler) RegisterTransaction(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	itemID := c.Params("id")

	var req struct {
		Type     string  `json:"type"`
		Quantity float64 `json:"quantity"`
		Notes    string  `json:"notes"`
	}

	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Dados inválidos"})
	}

	if req.Type != "in" && req.Type != "out" {
		return c.Status(400).JSON(fiber.Map{"error": "Tipo de transação inválido (in/out)"})
	}
	if req.Quantity <= 0 {
		return c.Status(400).JSON(fiber.Map{"error": "Quantidade deve ser maior que zero"})
	}

	// Transaction to update item qty and save log
	tx := database.DB.Begin()

	var item domain.InventoryItem
	if err := tx.Where("id = ? AND clinic_id = ?", itemID, clinicID).First(&item).Error; err != nil {
		tx.Rollback()
		return c.Status(404).JSON(fiber.Map{"error": "Item não encontrado"})
	}

	if req.Type == "in" {
		item.Quantity += req.Quantity
	} else {
		// allow negative but generally means out of stock
		item.Quantity -= req.Quantity
	}

	if err := tx.Save(&item).Error; err != nil {
		tx.Rollback()
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao atualizar quantidade do item"})
	}

	transaction := domain.InventoryTransaction{
		ClinicID:        clinicID,
		InventoryItemID: item.ID,
		Type:            req.Type,
		Quantity:        req.Quantity,
		Notes:           req.Notes,
		Date:            time.Now(),
	}

	if err := tx.Create(&transaction).Error; err != nil {
		tx.Rollback()
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao salvar transação de estoque"})
	}

	tx.Commit()

	return c.Status(201).JSON(fiber.Map{
		"message": "Transação registrada com sucesso",
		"item":    item,
	})
}

func (h *InventoryHandler) GetTransactions(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))
	itemID := c.Params("id")

	var transactions []domain.InventoryTransaction
	if err := database.DB.Where("clinic_id = ? AND inventory_item_id = ?", clinicID, itemID).Order("date desc").Find(&transactions).Error; err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Erro ao buscar transações"})
	}

	if transactions == nil {
		transactions = []domain.InventoryTransaction{}
	}

	return c.JSON(transactions)
}
