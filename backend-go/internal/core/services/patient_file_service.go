package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
)

type PatientFileService struct {
	fileRepo *repositories.PatientFileRepository
}

func NewPatientFileService() *PatientFileService {
	return &PatientFileService{
		fileRepo: repositories.NewPatientFileRepository(),
	}
}

func (s *PatientFileService) CreateFile(clinicID, patientID uint, fileName, filePath, fileType, category string) (*domain.PatientFile, error) {
	if filePath == "" {
		return nil, errors.New("o caminho do arquivo é obrigatório")
	}

	file := &domain.PatientFile{
		ClinicID:  clinicID,
		PatientID: patientID,
		FileName:  fileName,
		FilePath:  filePath,
		FileType:  fileType,
		Category:  category,
	}

	err := s.fileRepo.Create(file)
	if err != nil {
		return nil, err
	}
	return file, nil
}

func (s *PatientFileService) ListByPatient(clinicID, patientID uint) ([]domain.PatientFile, error) {
	return s.fileRepo.ListByPatient(clinicID, patientID)
}

func (s *PatientFileService) DeleteFile(clinicID, id uint) error {
	return s.fileRepo.Delete(id, clinicID)
}
