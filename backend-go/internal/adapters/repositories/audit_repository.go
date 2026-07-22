package repositories

import (
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"
)

type AuditRepository struct{}

func NewAuditRepository() *AuditRepository {
	return &AuditRepository{}
}

func (r *AuditRepository) Create(log *domain.AuditLog) error {
	return database.DB.Create(log).Error
}
