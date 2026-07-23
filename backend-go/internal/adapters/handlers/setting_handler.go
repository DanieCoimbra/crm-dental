package handlers

import (
	"dental-crm-api/internal/core/services"
	"strconv"
	"time"

	"github.com/gofiber/fiber/v2"
)

type SettingHandler struct {
	settingService *services.SettingService
}

func NewSettingHandler() *SettingHandler {
	return &SettingHandler{
		settingService: services.NewSettingService(),
	}
}

type SaveSettingRequest struct {
	Settings map[string]string `json:"settings"`
}

func (h *SettingHandler) List(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	settings, err := h.settingService.ListSettings(clinicID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Erro ao buscar configurações"})
	}

	return c.JSON(settings)
}

func (h *SettingHandler) Save(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var req SaveSettingRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Dados inválidos"})
	}

	for key, value := range req.Settings {
		err := h.settingService.SaveSetting(clinicID, key, value)
		if err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Erro ao salvar: " + err.Error()})
		}
	}

	return c.JSON(fiber.Map{"message": "Configurações salvas com sucesso"})
}

func (h *SettingHandler) Holidays(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	yearStr := c.Query("year", strconv.Itoa(time.Now().Year()))
	year, err := strconv.Atoi(yearStr)
	if err != nil {
		year = time.Now().Year()
	}

	holidays := h.settingService.GetHolidaysForYear(clinicID, year)
	return c.JSON(holidays)
}

func (h *SettingHandler) Export(c *fiber.Ctx) error {
	// Mock: Em um cenário real, geraria um zip com CSVs de todas as tabelas ou SQL dump
	c.Set("Content-Type", "application/json")
	c.Set("Content-Disposition", "attachment; filename=\"export_full.json\"")
	return c.SendString(`{"status": "success", "message": "Exportação completa mockada", "data": {}}`)
}

func (h *SettingHandler) Import(c *fiber.Ctx) error {
	// Mock: Receberia um arquivo de dump e restauraria os dados
	file, err := c.FormFile("file")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Arquivo não encontrado"})
	}

	return c.JSON(fiber.Map{
		"message":  "Importação concluída com sucesso! (Mock)",
		"filename": file.Filename,
	})
}
