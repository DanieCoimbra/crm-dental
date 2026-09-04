package domain

import (
	"time"
)

type BudgetStatus string

const (
	BudgetStatusDraft    BudgetStatus = "draft"
	BudgetStatusSent     BudgetStatus = "sent"
	BudgetStatusApproved BudgetStatus = "approved"
	BudgetStatusRejected BudgetStatus = "rejected"
)

type Budget struct {
	ID               uint         `gorm:"primaryKey" json:"id"`
	ClinicID         uint         `gorm:"index;not null" json:"clinic_id"`
	PatientID        uint         `gorm:"index;not null" json:"patient_id"`
	Patient          *Patient     `gorm:"foreignKey:PatientID" json:"patient,omitempty"`
	DentistID        *uint        `gorm:"index" json:"dentist_id"`
	Dentist          *User        `gorm:"foreignKey:DentistID" json:"dentist,omitempty"`
	TotalAmountCents int64        `gorm:"not null" json:"total_amount_cents"`
	DiscountCents    int64        `gorm:"default:0" json:"discount_cents"`
	FinalAmountCents int64        `gorm:"not null" json:"final_amount_cents"`
	Status           BudgetStatus `gorm:"size:20;not null;default:'draft'" json:"status"`
	Notes            string       `json:"notes"`
	Items            []BudgetItem `gorm:"foreignKey:BudgetID;constraint:OnDelete:CASCADE;" json:"items,omitempty"`
	CreatedAt        time.Time    `json:"created_at"`
	UpdatedAt        time.Time    `json:"updated_at"`
}

type BudgetItem struct {
	ID          uint       `gorm:"primaryKey" json:"id"`
	BudgetID    uint       `gorm:"index;not null" json:"budget_id"`
	ProcedureID *uint      `gorm:"index" json:"procedure_id"`
	Procedure   *Procedure `gorm:"foreignKey:ProcedureID" json:"procedure,omitempty"`
	ToothNumber *int       `json:"tooth_number"`
	Face        string     `gorm:"size:20" json:"face"`
	PriceCents  int64      `gorm:"not null" json:"price_cents"`
	Quantity    int        `gorm:"default:1" json:"quantity"`
}
