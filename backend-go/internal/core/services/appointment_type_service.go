package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
)

type AppointmentTypeService struct {
	typeRepo *repositories.AppointmentTypeRepository
}

func NewAppointmentTypeService() *AppointmentTypeService {
	return &AppointmentTypeService{
		typeRepo: repositories.NewAppointmentTypeRepository(),
	}
}

func (s *AppointmentTypeService) ListTypes(clinicID uint) ([]domain.AppointmentType, error) {
	return s.typeRepo.ListByClinic(clinicID)
}

func (s *AppointmentTypeService) CreateType(clinicID uint, name string, description string, durationMinutes int, color string) (*domain.AppointmentType, error) {
	if name == "" {
		return nil, errors.New("o nome da especialidade é obrigatório")
	}

	apptType := &domain.AppointmentType{
		ClinicID:        clinicID,
		Name:            name,
		Description:     description,
		DurationMinutes: durationMinutes,
		Color:           color,
	}

	if err := s.typeRepo.Create(apptType); err != nil {
		return nil, err
	}

	return apptType, nil
}

func (s *AppointmentTypeService) DeleteType(clinicID, id, deletedBy uint) error {
	return s.typeRepo.Delete(id, clinicID, deletedBy)
}

func (s *AppointmentTypeService) UpdateType(clinicID, id uint, name string, description string, durationMinutes int, color string) (*domain.AppointmentType, error) {
	apptType, err := s.typeRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("especialidade não encontrada")
	}

	if name != "" {
		apptType.Name = name
	}
	// Description can be empty
	apptType.Description = description
	if durationMinutes > 0 {
		apptType.DurationMinutes = durationMinutes
	}
	if color != "" {
		apptType.Color = color
	}

	if err := s.typeRepo.Update(apptType); err != nil {
		return nil, err
	}
	return apptType, nil
}

func (s *AppointmentTypeService) GetType(clinicID, id uint) (*domain.AppointmentType, error) {
	apptType, err := s.typeRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("especialidade não encontrada")
	}
	return apptType, nil
}
