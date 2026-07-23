package domain

import (
	"time"
)

type AuditLog struct {
	ID       uint    `gorm:"primaryKey" json:"id"`
	ClinicID uint    `gorm:"not null;index" json:"clinic_id"`
	Clinic   *Clinic `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`

	UserID uint  `gorm:"not null;index" json:"user_id"`
	User   *User `gorm:"foreignKey:UserID" json:"user,omitempty"`

	Action   string `gorm:"size:100;not null" json:"action"` // ex: "CREATE", "READ", "UPDATE", "DELETE"
	Entity   string `gorm:"size:100;not null" json:"entity"` // ex: "Prescription", "ClinicalEvolution", "Patient"
	EntityID uint   `gorm:"not null" json:"entity_id"`

	IPAddress string `gorm:"size:45" json:"ip_address"`   // ipv4 or ipv6
	UserAgent string `gorm:"type:text" json:"user_agent"` // browser / device

	Details string `gorm:"type:text" json:"details"` // extra contextual info (e.g. "Viewed patient EMR")

	CreatedAt time.Time `json:"created_at"`
}
