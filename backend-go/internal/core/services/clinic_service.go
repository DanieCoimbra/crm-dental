package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
)

type ClinicService struct {
	clinicRepo *repositories.ClinicRepository
}

func NewClinicService() *ClinicService {
	return &ClinicService{
		clinicRepo: repositories.NewClinicRepository(),
	}
}

func (s *ClinicService) GetClinic(id uint) (*domain.Clinic, error) {
	clinic, err := s.clinicRepo.FindByID(id)
	if err != nil {
		return nil, errors.New("clínica não encontrada")
	}
	return clinic, nil
}

func (s *ClinicService) UpdateClinic(id uint, name, cnpj, email, phone string) (*domain.Clinic, error) {
	clinic, err := s.clinicRepo.FindByID(id)
	if err != nil {
		return nil, errors.New("clínica não encontrada")
	}

	if name == "" {
		return nil, errors.New("o nome da clínica é obrigatório")
	}
	if cnpj == "" {
		return nil, errors.New("o CNPJ da clínica é obrigatório")
	}

	clinic.Name = name
	clinic.CNPJ = cnpj
	clinic.Email = email
	clinic.Phone = phone

	if err := s.clinicRepo.Update(clinic); err != nil {
		return nil, err
	}
	return clinic, nil
}
