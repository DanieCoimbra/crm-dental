package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
	"strings"
)

type MedicalDocumentService struct {
	docRepo     *repositories.MedicalDocumentRepository
	patientRepo *repositories.PatientRepository
}

func NewMedicalDocumentService() *MedicalDocumentService {
	return &MedicalDocumentService{
		docRepo:     repositories.NewMedicalDocumentRepository(),
		patientRepo: repositories.NewPatientRepository(),
	}
}

func (s *MedicalDocumentService) CreateDocument(clinicID, patientID, dentistID uint, docType, title, content string) (*domain.MedicalDocument, error) {
	docType = strings.ToUpper(strings.TrimSpace(docType))
	if docType == "" {
		return nil, errors.New("o tipo do documento é obrigatório")
	}
	if docType != domain.DocumentTypeAtestado && docType != domain.DocumentTypeReceita && docType != domain.DocumentTypeEncaminhamento {
		return nil, errors.New("tipo de documento inválido. Tipos aceitos: ATESTADO, RECEITA, ENCAMINHAMENTO")
	}

	title = strings.TrimSpace(title)
	if title == "" {
		return nil, errors.New("o título do documento é obrigatório")
	}

	content = strings.TrimSpace(content)
	if content == "" {
		return nil, errors.New("o conteúdo do documento é obrigatório")
	}

	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	doc := &domain.MedicalDocument{
		ClinicID:  clinicID,
		PatientID: patientID,
		DentistID: dentistID,
		Type:      docType,
		Title:     title,
		Content:   content,
	}

	err = s.docRepo.Create(doc)
	if err != nil {
		return nil, err
	}

	return doc, nil
}

func (s *MedicalDocumentService) ListDocumentsByPatient(clinicID, patientID uint) ([]domain.MedicalDocument, error) {
	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	return s.docRepo.ListByPatient(clinicID, patientID)
}

func (s *MedicalDocumentService) GetDocumentByID(id, clinicID uint) (*domain.MedicalDocument, error) {
	doc, err := s.docRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("documento não encontrado")
	}
	return doc, nil
}

func (s *MedicalDocumentService) DeleteDocument(id, clinicID uint) error {
	_, err := s.docRepo.FindByID(id, clinicID)
	if err != nil {
		return errors.New("documento não encontrado")
	}
	return s.docRepo.Delete(id, clinicID)
}
