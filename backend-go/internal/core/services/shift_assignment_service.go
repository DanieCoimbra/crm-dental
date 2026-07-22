package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
	"time"
)

type ShiftAssignmentService struct {
	shiftRepo *repositories.ShiftAssignmentRepository
}

func NewShiftAssignmentService() *ShiftAssignmentService {
	return &ShiftAssignmentService{
		shiftRepo: repositories.NewShiftAssignmentRepository(),
	}
}

func (s *ShiftAssignmentService) CreateShift(clinicID, doctorID, roomID uint, date *time.Time, shift string) (*domain.ShiftAssignment, error) {
	if doctorID == 0 || roomID == 0 {
		return nil, errors.New("doutor e sala são obrigatórios")
	}

	assignment := &domain.ShiftAssignment{
		ClinicID: clinicID,
		DoctorID: doctorID,
		RoomID:   roomID,
		Date:     date,
		Shift:    shift,
	}

	err := s.shiftRepo.Create(assignment)
	if err != nil {
		return nil, err
	}
	return assignment, nil
}

func (s *ShiftAssignmentService) ListShifts(clinicID uint) ([]domain.ShiftAssignment, error) {
	return s.shiftRepo.ListByClinic(clinicID)
}

func (s *ShiftAssignmentService) DeleteShift(clinicID, id uint) error {
	return s.shiftRepo.Delete(id, clinicID)
}
