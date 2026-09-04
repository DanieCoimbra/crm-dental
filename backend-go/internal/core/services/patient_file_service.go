package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
	"strings"
)

type PatientFileService struct {
	fileRepo    *repositories.PatientFileRepository
	patientRepo *repositories.PatientRepository
}

func NewPatientFileService() *PatientFileService {
	return &PatientFileService{
		fileRepo:    repositories.NewPatientFileRepository(),
		patientRepo: repositories.NewPatientRepository(),
	}
}

func (s *PatientFileService) CreateFileRecord(clinicID, patientID, uploadedBy uint, fileName, fileURL, fileTypeStr, category string, fileSize int64) (*domain.PatientFile, error) {
	if fileURL == "" {
		return nil, errors.New("a URL do arquivo é obrigatória")
	}

	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}

	fileType := domain.PatientFileType(strings.ToUpper(strings.TrimSpace(fileTypeStr)))
	if !domain.ValidatePatientFileType(fileType) {
		fileType = domain.FileTypeOutro
	}

	file := &domain.PatientFile{
		ClinicID:    clinicID,
		PatientID:   patientID,
		FileName:    fileName,
		FileURL:     fileURL,
		SupabaseUrl: fileURL,
		FileType:    fileType,
		FileSize:    fileSize,
		UploadedBy:  uploadedBy,
		Category:    category,
	}

	err = s.fileRepo.Create(file)
	if err != nil {
		return nil, err
	}
	return file, nil
}

func (s *PatientFileService) CreateFile(clinicID, patientID uint, fileName, supabaseUrl, fileType, category string) (*domain.PatientFile, error) {
	return s.CreateFileRecord(clinicID, patientID, 0, fileName, supabaseUrl, fileType, category, 0)
}

func (s *PatientFileService) ListByPatient(clinicID, patientID uint) ([]domain.PatientFile, error) {
	_, err := s.patientRepo.FindByID(patientID, clinicID)
	if err != nil {
		return nil, errors.New("paciente não encontrado ou não pertence a esta clínica")
	}
	return s.fileRepo.ListByPatient(clinicID, patientID)
}

func (s *PatientFileService) DeleteFile(clinicID, id uint) error {
	_, err := s.fileRepo.FindByID(id, clinicID)
	if err != nil {
		return errors.New("arquivo não encontrado ou não pertence a esta clínica")
	}
	return s.fileRepo.Delete(id, clinicID)
}
