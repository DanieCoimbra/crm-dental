package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
	"time"
)

type FinancialService struct {
	repo *repositories.FinancialRepository
}

func NewFinancialService() *FinancialService {
	return &FinancialService{
		repo: repositories.NewFinancialRepository(),
	}
}

// CreateTransaction handles creating a transaction. If installments > 1, it calculates and creates them.
func (s *FinancialService) CreateTransaction(clinicID uint, patientID *uint, txType, category, desc string, totalAmount int64, paymentMethod string, dueDate time.Time, totalInstallments int) (*domain.ClinicTransaction, error) {
	if totalAmount <= 0 {
		return nil, errors.New("o valor total deve ser maior que zero")
	}
	if totalInstallments <= 0 {
		totalInstallments = 1
	}

	tx := &domain.ClinicTransaction{
		ClinicID:      clinicID,
		PatientID:     patientID,
		Type:          txType,
		Category:      category,
		Description:   desc,
		TotalAmount:   totalAmount,
		PaymentMethod: paymentMethod,
		Status:        "pending",
		DueDate:       dueDate,
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
	}

	if totalInstallments == 1 {
		// Just one installment
		installment := domain.ClinicInstallment{
			ClinicID:    clinicID,
			Number:      1,
			TotalNumber: 1,
			AmountCents: totalAmount,
			DueDate:     dueDate,
			Status:      "pending",
			CreatedAt:   time.Now(),
			UpdatedAt:   time.Now(),
		}
		tx.Installments = []domain.ClinicInstallment{installment}
	} else {
		// Calculate installments
		baseAmount := totalAmount / int64(totalInstallments)
		remainder := totalAmount % int64(totalInstallments)

		for i := 1; i <= totalInstallments; i++ {
			amount := baseAmount
			if i == 1 {
				amount += remainder
			}

			// Increment due date by i-1 months
			instDueDate := dueDate.AddDate(0, i-1, 0)

			installment := domain.ClinicInstallment{
				ClinicID:    clinicID,
				Number:      i,
				TotalNumber: totalInstallments,
				AmountCents: amount,
				DueDate:     instDueDate,
				Status:      "pending",
				CreatedAt:   time.Now(),
				UpdatedAt:   time.Now(),
			}
			tx.Installments = append(tx.Installments, installment)
		}
	}

	if err := s.repo.CreateTransactionWithInstallments(tx); err != nil {
		return nil, err
	}
	return tx, nil
}

func (s *FinancialService) GetTransactions(clinicID uint, txType, status string) ([]domain.ClinicTransaction, error) {
	return s.repo.GetTransactionsByClinic(clinicID, txType, status)
}

func (s *FinancialService) RegisterInstallmentPayment(installmentID uint, clinicID uint) error {
	installment, err := s.repo.GetInstallmentByID(installmentID)
	if err != nil {
		return errors.New("parcela não encontrada")
	}
	if installment.ClinicID != clinicID {
		return errors.New("acesso negado")
	}
	if installment.Status == "paid" {
		return errors.New("parcela já está paga")
	}

	now := time.Now()
	installment.Status = "paid"
	installment.PaidAt = &now
	installment.UpdatedAt = now

	if err := s.repo.UpdateInstallment(installment); err != nil {
		return err
	}

	// Check if all installments for this transaction are paid
	installments, err := s.repo.GetInstallmentsByTransaction(installment.TransactionID)
	if err == nil {
		allPaid := true
		for _, inst := range installments {
			if inst.Status != "paid" {
				allPaid = false
				break
			}
		}

		if allPaid {
			tx, err := s.repo.GetTransactionByID(installment.TransactionID)
			if err == nil {
				tx.Status = "paid"
				tx.PaidAt = &now
				tx.UpdatedAt = now
				_ = s.repo.UpdateTransaction(tx)
			}
		}
	}

	return nil
}
