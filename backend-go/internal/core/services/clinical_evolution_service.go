package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"

	"github.com/microcosm-cc/bluemonday"
)

type ClinicalEvolutionService struct {
	evolutionRepo *repositories.ClinicalEvolutionRepository
}

func NewClinicalEvolutionService() *ClinicalEvolutionService {
	return &ClinicalEvolutionService{
		evolutionRepo: repositories.NewClinicalEvolutionRepository(),
	}
}

func (s *ClinicalEvolutionService) CreateEvolution(clinicID, patientID, userID uint, contentHtml string) (*domain.ClinicalEvolution, error) {
	if contentHtml == "" {
		return nil, errors.New("o conteúdo da evolução é obrigatório")
	}

	// Sanitização rigorosa do HTML para prevenir XSS
	p := bluemonday.UGCPolicy()
	sanitizedHtml := p.Sanitize(contentHtml)

	evolution := &domain.ClinicalEvolution{
		ClinicID:    clinicID,
		PatientID:   patientID,
		UserID:      userID,
		ContentHtml: sanitizedHtml,
	}

	err := s.evolutionRepo.Create(evolution)
	if err != nil {
		return nil, err
	}
	return evolution, nil
}

func (s *ClinicalEvolutionService) ListByPatient(clinicID, patientID uint) ([]domain.ClinicalEvolution, error) {
	return s.evolutionRepo.ListByPatient(clinicID, patientID)
}

func (s *ClinicalEvolutionService) DeleteEvolution(clinicID, id uint) error {
	return s.evolutionRepo.Delete(id, clinicID)
}
