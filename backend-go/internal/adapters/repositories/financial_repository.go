package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
	"gorm.io/gorm"
)

type FinancialRepository struct {
	db *gorm.DB
}

func NewFinancialRepository() *FinancialRepository {
	return &FinancialRepository{db: database.DB}
}

// CreateTransactionWithInstallments creates a transaction and its installments in a single DB transaction.
func (r *FinancialRepository) CreateTransactionWithInstallments(txData *domain.ClinicTransaction) error {
	return r.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(txData).Error; err != nil {
			return err
		}
		return nil
	})
}

// GetTransactionsByClinic fetches all transactions for a clinic, with optional filtering.
func (r *FinancialRepository) GetTransactionsByClinic(clinicID uint, transactionType string, status string) ([]domain.ClinicTransaction, error) {
	var transactions []domain.ClinicTransaction
	query := r.db.Preload("Installments").Where("clinic_id = ?", clinicID)

	if transactionType != "" {
		query = query.Where("type = ?", transactionType)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	err := query.Order("due_date asc").Find(&transactions).Error
	return transactions, err
}

// GetInstallmentsByTransaction fetches installments for a specific transaction.
func (r *FinancialRepository) GetInstallmentsByTransaction(transactionID uint) ([]domain.ClinicInstallment, error) {
	var installments []domain.ClinicInstallment
	err := r.db.Where("transaction_id = ?", transactionID).Order("number asc").Find(&installments).Error
	return installments, err
}

// UpdateInstallment updates an installment (e.g., when it is paid).
func (r *FinancialRepository) UpdateInstallment(installment *domain.ClinicInstallment) error {
	return r.db.Save(installment).Error
}

// GetInstallmentByID retrieves a single installment by ID
func (r *FinancialRepository) GetInstallmentByID(id uint) (*domain.ClinicInstallment, error) {
	var installment domain.ClinicInstallment
	err := r.db.First(&installment, id).Error
	return &installment, err
}

// UpdateTransaction updates the transaction (e.g., status changes to paid).
func (r *FinancialRepository) UpdateTransaction(transaction *domain.ClinicTransaction) error {
	return r.db.Save(transaction).Error
}

// GetTransactionByID retrieves a single transaction by ID
func (r *FinancialRepository) GetTransactionByID(id uint) (*domain.ClinicTransaction, error) {
	var transaction domain.ClinicTransaction
	err := r.db.Preload("Installments").First(&transaction, id).Error
	return &transaction, err
}
