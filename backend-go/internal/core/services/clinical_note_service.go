package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
	"time"
)

type ClinicalNoteService struct {
	noteRepo    *repositories.ClinicalNoteRepository
	patientRepo *repositories.PatientRepository
}

func NewClinicalNoteService() *ClinicalNoteService {
	return &ClinicalNoteService{
		noteRepo:    repositories.NewClinicalNoteRepository(),
		patientRepo: repositories.NewPatientRepository(),
	}
}

func (s *ClinicalNoteService) CreateNote(clinicID, patientID, dentistID uint, attendanceDate time.Time, chiefComplaint, diagnosis, procedureSummary string) (*domain.ClinicalNote, error) {
	if procedureSummary == "" {
		return nil, errors.New("o resumo do procedimento é obrigatório")
	}

	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	if attendanceDate.IsZero() {
		attendanceDate = time.Now()
	}

	note := &domain.ClinicalNote{
		ClinicID:         clinicID,
		PatientID:        patientID,
		DentistID:        dentistID,
		AttendanceDate:   attendanceDate,
		ChiefComplaint:   chiefComplaint,
		Diagnosis:        diagnosis,
		ProcedureSummary: procedureSummary,
	}

	err = s.noteRepo.Create(note)
	if err != nil {
		return nil, err
	}

	return note, nil
}

func (s *ClinicalNoteService) ListNotesByPatient(clinicID, patientID uint) ([]domain.ClinicalNote, error) {
	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	return s.noteRepo.ListByPatient(clinicID, patientID)
}
