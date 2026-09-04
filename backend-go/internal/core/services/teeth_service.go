package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
	"fmt"
	"strings"
)

type TeethService struct {
	teethRepo   *repositories.TeethRepository
	patientRepo *repositories.PatientRepository
}

func NewTeethService() *TeethService {
	return &TeethService{
		teethRepo:   repositories.NewTeethRepository(),
		patientRepo: repositories.NewPatientRepository(),
	}
}

func (s *TeethService) GetTeethByPatient(clinicID, patientID uint) ([]domain.TeethStatus, error) {
	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}
	return s.teethRepo.ListByPatient(clinicID, patientID)
}

func (s *TeethService) GetTeethByToothNumber(clinicID, patientID uint, toothNumber int) ([]domain.TeethStatus, error) {
	if !domain.ValidateFDITooth(toothNumber) {
		return nil, fmt.Errorf("número de dente inválido pela notação FDI: %d", toothNumber)
	}

	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	return s.teethRepo.GetByToothNumber(clinicID, patientID, toothNumber)
}

func (s *TeethService) UpdateToothStatus(clinicID, patientID, userID uint, toothNumber int, faceStr, conditionStr, notes string) (*domain.TeethStatus, error) {
	if !domain.ValidateFDITooth(toothNumber) {
		return nil, fmt.Errorf("número de dente inválido pela notação FDI: %d", toothNumber)
	}

	face := domain.ToothFace(strings.ToUpper(strings.TrimSpace(faceStr)))
	if face == "" {
		face = domain.FaceGeral
	}
	if !domain.ValidateToothFace(face) {
		return nil, fmt.Errorf("face anatômica inválida: %s", faceStr)
	}

	condition := domain.ToothCondition(strings.ToUpper(strings.TrimSpace(conditionStr)))
	if !domain.ValidateToothCondition(condition) {
		return nil, fmt.Errorf("condição clínica inválida: %s", conditionStr)
	}

	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	existingStatus, err := s.teethRepo.GetByToothAndFace(clinicID, patientID, toothNumber, face)
	if err != nil {
		return nil, err
	}

	var previousCond *domain.ToothCondition
	if existingStatus != nil {
		prev := existingStatus.Condition
		previousCond = &prev
	} else {
		defaultHigido := domain.ConditionHigido
		previousCond = &defaultHigido
	}

	newStatus := &domain.TeethStatus{
		ClinicID:    clinicID,
		PatientID:   patientID,
		ToothNumber: toothNumber,
		Face:        face,
		Condition:   condition,
		UpdatedBy:   userID,
	}

	if err := s.teethRepo.UpsertStatus(newStatus); err != nil {
		return nil, fmt.Errorf("erro ao salvar estado do dente: %w", err)
	}

	history := &domain.TeethHistory{
		ClinicID:          clinicID,
		PatientID:         patientID,
		TeethStatusID:     &newStatus.ID,
		ToothNumber:       toothNumber,
		Face:              face,
		PreviousCondition: previousCond,
		NewCondition:      condition,
		Notes:             notes,
		CreatedBy:         userID,
	}

	if err := s.teethRepo.CreateHistory(history); err != nil {
		return nil, fmt.Errorf("erro ao registrar histórico imutável: %w", err)
	}

	return newStatus, nil
}

func (s *TeethService) GetTeethHistory(clinicID, patientID uint) ([]domain.TeethHistory, error) {
	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}
	return s.teethRepo.ListHistoryByPatient(clinicID, patientID)
}
