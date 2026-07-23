package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
)

type PatientService struct {
	patientRepo *repositories.PatientRepository
}

func NewPatientService() *PatientService {
	return &PatientService{
		patientRepo: repositories.NewPatientRepository(),
	}
}

func (s *PatientService) CreatePatient(clinicID uint, name, cpf, email, phone, cep, street, neighborhood, number, healthInsurance string) (*domain.Patient, error) {
	if name == "" {
		return nil, errors.New("o nome do paciente é obrigatório")
	}

	patient := &domain.Patient{
		ClinicID:        clinicID,
		Name:            name,
		CPF:             cpf,
		Email:           email,
		Phone:           phone,
		Cep:             cep,
		Street:          street,
		Neighborhood:    neighborhood,
		Number:          number,
		HealthInsurance: healthInsurance,
	}

	err := s.patientRepo.Create(patient)
	if err != nil {
		return nil, err
	}
	return patient, nil
}

func (s *PatientService) ListPatients(clinicID uint, search string, page int) ([]domain.Patient, error) {
	return s.patientRepo.ListByClinic(clinicID, search, page)
}

func (s *PatientService) DeletePatient(clinicID uint, patientID uint, deletedBy uint) error {
	// Garantir que o paciente pertence à clínica antes de deletar
	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	return s.patientRepo.Delete(patientID, deletedBy)
}

func (s *PatientService) UpdatePatient(clinicID, id uint, name, cpf, email, phone, cep, street, neighborhood, number, healthInsurance string) (*domain.Patient, error) {
	patient, err := s.patientRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	if name == "" {
		return nil, errors.New("o nome do paciente é obrigatório")
	}

	patient.Name = name
	patient.CPF = cpf
	patient.Email = email
	patient.Phone = phone
	patient.Cep = cep
	patient.Street = street
	patient.Neighborhood = neighborhood
	patient.Number = number
	patient.HealthInsurance = healthInsurance

	if err := s.patientRepo.Update(patient); err != nil {
		return nil, err
	}
	return patient, nil
}

func (s *PatientService) UpdateEMR(clinicID, id uint, medicalHistory, notes string) (*domain.Patient, error) {
	patient, err := s.patientRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	patient.MedicalHistory = medicalHistory
	patient.Notes = notes

	if err := s.patientRepo.Update(patient); err != nil {
		return nil, err
	}
	return patient, nil
}

func (s *PatientService) GetPatient(clinicID, id uint) (*domain.Patient, error) {
	patient, err := s.patientRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado")
	}
	return patient, nil
}
