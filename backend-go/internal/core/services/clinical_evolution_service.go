package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"encoding/json"
	"errors"
	"strings"

	"github.com/microcosm-cc/bluemonday"
)

type ClinicalEvolutionService struct {
	evolutionRepo *repositories.ClinicalEvolutionRepository
}

func NewClinicalEvolutionService() *ClinicalEvolutionService {
	return &ClinicalEvolutionService{
		evolutionRepo: repositories.NewClinicalEvolutionRepository(),
	}
}

const maxPageNumber = 1000

func (s *ClinicalEvolutionService) CreateEvolution(clinicID, patientID, userID uint, contentHtml string) (*domain.ClinicalEvolution, error) {
	trimmed := strings.TrimSpace(contentHtml)
	if trimmed == "" {
		return nil, errors.New("o conteúdo da evolução é obrigatório")
	}

	var sanitizedContent string

	// Se o conteúdo for um JSON do Quill Delta (array de operações ou documento), desarmamos HTML nocivo nos inserts
	if json.Valid([]byte(trimmed)) && (strings.HasPrefix(trimmed, "[") || strings.HasPrefix(trimmed, "{")) {
		sanitizedContent = sanitizeQuillDeltaText(trimmed)
	} else {
		// Sanitização rigorosa de HTML tradicional para prevenir XSS
		p := bluemonday.UGCPolicy()
		sanitizedContent = p.Sanitize(trimmed)
	}

	evolution := &domain.ClinicalEvolution{
		ClinicID:    clinicID,
		PatientID:   patientID,
		UserID:      userID,
		ContentHtml: sanitizedContent,
	}

	err := s.evolutionRepo.Create(evolution)
	if err != nil {
		return nil, err
	}
	return evolution, nil
}

func sanitizeQuillDeltaText(rawJSON string) string {
	p := bluemonday.UGCPolicy()

	// Tenta desestruturar como array de operações Delta do Quill
	var ops []map[string]interface{}
	if err := json.Unmarshal([]byte(rawJSON), &ops); err == nil {
		for _, op := range ops {
			if insertVal, exists := op["insert"].(string); exists {
				op["insert"] = p.Sanitize(insertVal)
			}
		}
		if bytes, err := json.Marshal(ops); err == nil {
			return string(bytes)
		}
	}

	// Tenta desestruturar como objeto com chave "ops"
	var doc map[string]interface{}
	if err := json.Unmarshal([]byte(rawJSON), &doc); err == nil {
		if opsSlice, ok := doc["ops"].([]interface{}); ok {
			for _, opItem := range opsSlice {
				if opMap, ok := opItem.(map[string]interface{}); ok {
					if insertVal, exists := opMap["insert"].(string); exists {
						opMap["insert"] = p.Sanitize(insertVal)
					}
				}
			}
			if bytes, err := json.Marshal(doc); err == nil {
				return string(bytes)
			}
		}
	}

	return rawJSON
}

func (s *ClinicalEvolutionService) ListByPatient(clinicID, patientID uint, page, limit int) ([]domain.ClinicalEvolution, int64, error) {
	if page < 1 {
		page = 1
	}
	if page > maxPageNumber {
		page = maxPageNumber
	}
	if limit < 1 || limit > 100 {
		limit = 20
	}
	offset := (page - 1) * limit

	return s.evolutionRepo.ListByPatient(clinicID, patientID, limit, offset)
}

func (s *ClinicalEvolutionService) DeleteEvolution(clinicID, id uint) error {
	return s.evolutionRepo.Delete(id, clinicID)
}

