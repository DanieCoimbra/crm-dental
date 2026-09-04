package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
	"fmt"
	"time"
)

type BudgetService struct {
	repo             *repositories.BudgetRepository
	financialService *FinancialService
}

func NewBudgetService() *BudgetService {
	return &BudgetService{
		repo:             repositories.NewBudgetRepository(),
		financialService: NewFinancialService(),
	}
}

func (s *BudgetService) CreateBudget(clinicID uint, patientID uint, dentistID *uint, items []domain.BudgetItem, discountCents int64, notes string) (*domain.Budget, error) {
	if patientID == 0 {
		return nil, errors.New("o ID do paciente é obrigatório")
	}
	if len(items) == 0 {
		return nil, errors.New("o orçamento deve conter ao menos um item")
	}

	var totalAmount int64
	for i := range items {
		if items[i].Quantity <= 0 {
			items[i].Quantity = 1
		}
		if items[i].PriceCents < 0 {
			return nil, errors.New("o preço do item não pode ser negativo")
		}
		totalAmount += items[i].PriceCents * int64(items[i].Quantity)
	}

	if discountCents < 0 {
		discountCents = 0
	}

	finalAmount := totalAmount - discountCents
	if finalAmount < 0 {
		finalAmount = 0
	}

	budget := &domain.Budget{
		ClinicID:         clinicID,
		PatientID:        patientID,
		DentistID:        dentistID,
		TotalAmountCents: totalAmount,
		DiscountCents:    discountCents,
		FinalAmountCents: finalAmount,
		Status:           domain.BudgetStatusDraft,
		Notes:            notes,
		Items:            items,
		CreatedAt:        time.Now(),
		UpdatedAt:        time.Now(),
	}

	if err := s.repo.Create(budget); err != nil {
		return nil, err
	}

	return s.repo.GetByID(budget.ID, clinicID)
}

func (s *BudgetService) GetBudget(id uint, clinicID uint) (*domain.Budget, error) {
	budget, err := s.repo.GetByID(id, clinicID)
	if err != nil {
		return nil, errors.New("orçamento não encontrado")
	}
	return budget, nil
}

func (s *BudgetService) ListBudgets(clinicID uint, patientID *uint, status string) ([]domain.Budget, error) {
	return s.repo.ListByClinic(clinicID, patientID, status)
}

func (s *BudgetService) ApproveBudget(id uint, clinicID uint, totalInstallments int, paymentMethod string, firstDueDate *time.Time) (*domain.Budget, *domain.ClinicTransaction, error) {
	budget, err := s.repo.GetByID(id, clinicID)
	if err != nil {
		return nil, nil, errors.New("orçamento não encontrado")
	}

	if budget.Status == domain.BudgetStatusApproved {
		return nil, nil, errors.New("orçamento já foi aprovado")
	}

	budget.Status = domain.BudgetStatusApproved
	budget.UpdatedAt = time.Now()

	if err := s.repo.Update(budget); err != nil {
		return nil, nil, err
	}

	if paymentMethod == "" {
		paymentMethod = "Cartão de Crédito"
	}
	if totalInstallments <= 0 {
		totalInstallments = 1
	}

	dueDate := time.Now()
	if firstDueDate != nil && !firstDueDate.IsZero() {
		dueDate = *firstDueDate
	}

	desc := fmt.Sprintf("Orçamento #%d", budget.ID)
	if budget.Patient != nil && budget.Patient.Name != "" {
		desc = fmt.Sprintf("Orçamento #%d - %s", budget.ID, budget.Patient.Name)
	}

	tx, err := s.financialService.CreateTransaction(
		clinicID,
		&budget.PatientID,
		"income",
		"Procedimento",
		desc,
		budget.FinalAmountCents,
		paymentMethod,
		dueDate,
		totalInstallments,
	)
	if err != nil {
		return budget, nil, fmt.Errorf("erro ao gerar financeiro do orçamento: %w", err)
	}

	tx.BudgetID = &budget.ID
	_ = s.financialService.UpdateTransaction(tx)

	return budget, tx, nil
}

func (s *BudgetService) RejectBudget(id uint, clinicID uint) (*domain.Budget, error) {
	budget, err := s.repo.GetByID(id, clinicID)
	if err != nil {
		return nil, errors.New("orçamento não encontrado")
	}

	if budget.Status == domain.BudgetStatusApproved {
		return nil, errors.New("não é possível rejeitar um orçamento já aprovado")
	}

	budget.Status = domain.BudgetStatusRejected
	budget.UpdatedAt = time.Now()

	if err := s.repo.Update(budget); err != nil {
		return nil, err
	}

	return budget, nil
}
