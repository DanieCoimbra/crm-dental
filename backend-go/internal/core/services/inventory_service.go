package services

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"log"
	"time"

	"gorm.io/gorm"
)

type InventoryService struct{}

func NewInventoryService() *InventoryService {
	return &InventoryService{}
}

// ProcessAppointmentMaterials handles the F05 asynchronous deduction logic
func (s *InventoryService) ProcessAppointmentMaterials(clinicID, apptTypeID uint) {
	// Find the appointment type with its materials
	var apptType domain.AppointmentType
	if err := database.DB.Preload("Materials").Where("id = ? AND clinic_id = ?", apptTypeID, clinicID).First(&apptType).Error; err != nil {
		log.Printf("[F05] Erro ao buscar AppointmentType %d para dedução de estoque: %v\n", apptTypeID, err)
		return
	}

	if len(apptType.Materials) == 0 {
		return // Nothing to deduct
	}

	// Begin transaction
	err := database.DB.Transaction(func(tx *gorm.DB) error {
		for _, mat := range apptType.Materials {
			// Get current item
			var item domain.InventoryItem
			if err := tx.Where("id = ? AND clinic_id = ?", mat.InventoryItemID, clinicID).First(&item).Error; err != nil {
				log.Printf("[F05] Material %d não encontrado no estoque da clínica %d\n", mat.InventoryItemID, clinicID)
				continue
			}

			// Deduct
			item.Quantity -= mat.Quantity
			if err := tx.Save(&item).Error; err != nil {
				log.Printf("[F05] Erro ao deduzir %f do item %d\n", mat.Quantity, item.ID)
				return err
			}

			// Log transaction
			invTx := domain.InventoryTransaction{
				ClinicID:        clinicID,
				InventoryItemID: item.ID,
				Type:            "out",
				Quantity:        mat.Quantity,
				Notes:           "Baixa automática via F05 - Appointment.Completed",
				Date:            time.Now(),
			}

			if err := tx.Create(&invTx).Error; err != nil {
				log.Printf("[F05] Erro ao registrar transação de saída do item %d\n", item.ID)
				return err
			}

			if item.Quantity < 0 {
				log.Printf("[F05-ALERT] O item %s (ID %d) ficou com estoque negativo: %f\n", item.Name, item.ID, item.Quantity)
			}
		}
		return nil
	})

	if err != nil {
		log.Printf("[F05] Falha na transação de dedução de estoque: %v\n", err)
	} else {
		log.Printf("[F05] Dedução automática de estoque concluída com sucesso para o procedimento %d\n", apptTypeID)
	}
}
