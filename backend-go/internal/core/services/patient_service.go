package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
	"time"
)

type PatientService struct {
	patientRepo *repositories.PatientRepository
}

func NewPatientService() *PatientService {
	return &PatientService{
		patientRepo: repositories.NewPatientRepository(),
	}
}

type PatientInput struct {
	Name            string
	CPF             string
	Email           string
	Phone           string
	Cep             string
	Street          string
	Neighborhood    string
	Number          string
	HealthInsurance string
	Notes           string
	BirthDate       *time.Time
}

func (s *PatientService) CreatePatientWithInput(clinicID uint, input PatientInput) (*domain.Patient, error) {
	if input.Name == "" {
		return nil, errors.New("o nome do paciente é obrigatório")
	}

	patient := &domain.Patient{
		ClinicID:        clinicID,
		Name:            input.Name,
		CPF:             input.CPF,
		Email:           input.Email,
		Phone:           input.Phone,
		Cep:             input.Cep,
		Street:          input.Street,
		Neighborhood:    input.Neighborhood,
		Number:          input.Number,
		HealthInsurance: input.HealthInsurance,
		Notes:           input.Notes,
		BirthDate:       input.BirthDate,
	}

	err := s.patientRepo.Create(patient)
	if err != nil {
		return nil, err
	}
	return patient, nil
}

func (s *PatientService) CreatePatient(clinicID uint, name, cpf, email, phone, cep, street, neighborhood, number, healthInsurance string) (*domain.Patient, error) {
	return s.CreatePatientWithInput(clinicID, PatientInput{
		Name:            name,
		CPF:             cpf,
		Email:           email,
		Phone:           phone,
		Cep:             cep,
		Street:          street,
		Neighborhood:    neighborhood,
		Number:          number,
		HealthInsurance: healthInsurance,
	})
}

func (s *PatientService) ListPatients(clinicID uint, search string, page int) ([]domain.Patient, error) {
	return s.patientRepo.ListByClinic(clinicID, search, page)
}

func (s *PatientService) DeletePatient(clinicID uint, patientID uint, deletedBy uint) error {
	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	return s.patientRepo.Delete(patientID, clinicID, deletedBy)
}

func (s *PatientService) UpdatePatientWithInput(clinicID, id uint, input PatientInput) (*domain.Patient, error) {
	patient, err := s.patientRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	if input.Name == "" {
		return nil, errors.New("o nome do paciente é obrigatório")
	}

	patient.Name = input.Name
	patient.CPF = input.CPF
	patient.Email = input.Email
	patient.Phone = input.Phone
	patient.Cep = input.Cep
	patient.Street = input.Street
	patient.Neighborhood = input.Neighborhood
	patient.Number = input.Number
	patient.HealthInsurance = input.HealthInsurance
	if input.Notes != "" {
		patient.Notes = input.Notes
	}
	if input.BirthDate != nil {
		patient.BirthDate = input.BirthDate
	}

	if err := s.patientRepo.Update(patient); err != nil {
		return nil, err
	}
	return patient, nil
}

func (s *PatientService) UpdatePatient(clinicID, id uint, name, cpf, email, phone, cep, street, neighborhood, number, healthInsurance string) (*domain.Patient, error) {
	return s.UpdatePatientWithInput(clinicID, id, PatientInput{
		Name:            name,
		CPF:             cpf,
		Email:           email,
		Phone:           phone,
		Cep:             cep,
		Street:          street,
		Neighborhood:    neighborhood,
		Number:          number,
		HealthInsurance: healthInsurance,
	})
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
