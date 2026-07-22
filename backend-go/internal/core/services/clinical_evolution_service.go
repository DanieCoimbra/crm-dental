package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
)

type ClinicalEvolutionService struct {
	evolutionRepo *repositories.ClinicalEvolutionRepository
}

func NewClinicalEvolutionService() *ClinicalEvolutionService {
	return &ClinicalEvolutionService{
		evolutionRepo: repositories.NewClinicalEvolutionRepository(),
	}
}

func (s *ClinicalEvolutionService) CreateEvolution(clinicID, patientID, userID uint, content string) (*domain.ClinicalEvolution, error) {
	if content == "" {
		return nil, errors.New("o conteúdo da evolução é obrigatório")
	}

	evolution := &domain.ClinicalEvolution{
		ClinicID:  clinicID,
		PatientID: patientID,
		UserID:    userID,
		Content:   content,
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
