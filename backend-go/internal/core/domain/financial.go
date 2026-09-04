package domain

import (
	"time"
)

type ClinicTransaction struct {
	ID            uint                `gorm:"primaryKey" json:"id"`
	ClinicID      uint                `gorm:"index;not null" json:"clinic_id"`
	PatientID     *uint               `gorm:"index" json:"patient_id"`
	BudgetID      *uint               `gorm:"index" json:"budget_id,omitempty"`
	Type          string              `gorm:"index;not null" json:"type"` // "income" (receita), "expense" (despesa)
	Category      string              `json:"category"`                   // "Procedimento", "Aluguel", "Material"
	Description   string              `json:"description"`
	TotalAmount   int64               `json:"total_amount_cents"`
	PaymentMethod string              `json:"payment_method"`
	Status        string              `json:"status"` // "paid", "pending", "overdue", "canceled"
	DueDate       time.Time           `json:"due_date"`
	PaidAt        *time.Time          `json:"paid_at"`
	Installments  []ClinicInstallment `gorm:"foreignKey:TransactionID;constraint:OnDelete:CASCADE;" json:"installments,omitempty"`
	CreatedAt     time.Time           `json:"created_at"`
	UpdatedAt     time.Time           `json:"updated_at"`
}

type ClinicInstallment struct {
	ID            uint       `gorm:"primaryKey" json:"id"`
	TransactionID uint       `gorm:"index;not null" json:"transaction_id"`
	ClinicID      uint       `gorm:"index;not null" json:"clinic_id"`
	Number        int        `json:"number"`       // Ex: 1, 2, 3...
	TotalNumber   int        `json:"total_number"` // Ex: 6
	AmountCents   int64      `json:"amount_cents"`
	DueDate       time.Time  `json:"due_date"`
	PaidAt        *time.Time `json:"paid_at"`
	Status        string     `json:"status"` // "pending", "paid", "overdue"
	CreatedAt     time.Time  `json:"created_at"`
	UpdatedAt     time.Time  `json:"updated_at"`
}
