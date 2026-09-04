package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
	"strings"
	"time"
)

type ProcedureService struct {
	repo *repositories.ProcedureRepository
}

func NewProcedureService() *ProcedureService {
	return &ProcedureService{
		repo: repositories.NewProcedureRepository(),
	}
}

func (s *ProcedureService) CreateProcedure(clinicID uint, name, description string, basePriceCents int64, durationMinutes int, color string) (*domain.Procedure, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return nil, errors.New("o nome do procedimento é obrigatório")
	}
	if basePriceCents < 0 {
		return nil, errors.New("o preço base não pode ser negativo")
	}
	if durationMinutes <= 0 {
		durationMinutes = 30
	}
	if color == "" {
		color = "#2563EB"
	}

	proc := &domain.Procedure{
		ClinicID:        clinicID,
		Name:            name,
		Description:     description,
		BasePriceCents:  basePriceCents,
		DurationMinutes: durationMinutes,
		Color:           color,
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	if err := s.repo.Create(proc); err != nil {
		return nil, err
	}
	return proc, nil
}

func (s *ProcedureService) ListProcedures(clinicID uint) ([]domain.Procedure, error) {
	return s.repo.ListByClinic(clinicID)
}

func (s *ProcedureService) GetProcedure(id uint, clinicID uint) (*domain.Procedure, error) {
	proc, err := s.repo.GetByID(id, clinicID)
	if err != nil {
		return nil, errors.New("procedimento não encontrado")
	}
	return proc, nil
}

func (s *ProcedureService) UpdateProcedure(id uint, clinicID uint, name, description string, basePriceCents int64, durationMinutes int, color string) (*domain.Procedure, error) {
	proc, err := s.repo.GetByID(id, clinicID)
	if err != nil {
		return nil, errors.New("procedimento não encontrado")
	}

	name = strings.TrimSpace(name)
	if name == "" {
		return nil, errors.New("o nome do procedimento é obrigatório")
	}
	if basePriceCents < 0 {
		return nil, errors.New("o preço base não pode ser negativo")
	}

	proc.Name = name
	proc.Description = description
	proc.BasePriceCents = basePriceCents
	if durationMinutes > 0 {
		proc.DurationMinutes = durationMinutes
	}
	if color != "" {
		proc.Color = color
	}
	proc.UpdatedAt = time.Now()

	if err := s.repo.Update(proc); err != nil {
		return nil, err
	}
	return proc, nil
}

func (s *ProcedureService) DeleteProcedure(id uint, clinicID uint) error {
	_, err := s.repo.GetByID(id, clinicID)
	if err != nil {
		return errors.New("procedimento não encontrado")
	}
	return s.repo.Delete(id, clinicID)
}
