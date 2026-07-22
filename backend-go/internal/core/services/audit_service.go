package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
)

type AuditService struct {
	repo *repositories.AuditRepository
}

func NewAuditService() *AuditService {
	return &AuditService{
		repo: repositories.NewAuditRepository(),
	}
}

// LogAction cria um registro de auditoria assíncrono para não travar a requisição do usuário.
func (s *AuditService) LogAction(clinicID, userID uint, action, entity string, entityID uint, ip, userAgent, details string) {
	go func() {
		log := &domain.AuditLog{
			ClinicID:  clinicID,
			UserID:    userID,
			Action:    action,
			Entity:    entity,
			EntityID:  entityID,
			IPAddress: ip,
			UserAgent: userAgent,
			Details:   details,
		}
		_ = s.repo.Create(log)
	}()
}
