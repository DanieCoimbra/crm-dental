package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
	"sort"
	"strings"
	"time"
)

type WaitlistService struct {
	waitlistRepo *repositories.WaitlistRepository
}

func NewWaitlistService() *WaitlistService {
	return &WaitlistService{
		waitlistRepo: repositories.NewWaitlistRepository(),
	}
}

func (s *WaitlistService) CreateEntry(clinicID uint, patientID uint, doctorID *uint, apptTypeID *uint, preferredDays, preferredTimeRange, urgencyLevel, notes string) (*domain.Waitlist, error) {
	if patientID == 0 {
		return nil, errors.New("o ID do paciente é obrigatório")
	}

	waitlist := &domain.Waitlist{
		ClinicID:           clinicID,
		PatientID:          patientID,
		DoctorID:           doctorID,
		AppointmentTypeID:  apptTypeID,
		PreferredDays:      preferredDays,
		PreferredTimeRange: preferredTimeRange,
		UrgencyLevel:       urgencyLevel,
		Notes:              notes,
		Status:             "pending",
	}

	err := s.waitlistRepo.Create(waitlist)
	if err != nil {
		return nil, err
	}
	return waitlist, nil
}

func (s *WaitlistService) ListEntries(clinicID uint) ([]domain.Waitlist, error) {
	return s.waitlistRepo.ListByClinic(clinicID)
}

func (s *WaitlistService) DeleteEntry(clinicID uint, waitlistID uint) error {
	return s.waitlistRepo.Delete(waitlistID, clinicID)
}

func (s *WaitlistService) GetEntryByID(clinicID, id uint) (*domain.Waitlist, error) {
	return s.waitlistRepo.FindByID(id, clinicID)
}

func (s *WaitlistService) UpdateEntry(clinicID, id uint, doctorID, apptTypeID *uint, preferredDays, preferredTimeRange, urgencyLevel, notes, status string) (*domain.Waitlist, error) {
	entry, err := s.waitlistRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("entrada não encontrada ou não pertence a esta clínica")
	}

	entry.DoctorID = doctorID
	entry.AppointmentTypeID = apptTypeID
	entry.PreferredDays = preferredDays
	entry.PreferredTimeRange = preferredTimeRange
	entry.UrgencyLevel = urgencyLevel
	entry.Notes = notes
	if status != "" {
		entry.Status = status
	}

	if err := s.waitlistRepo.Update(entry); err != nil {
		return nil, err
	}
	return entry, nil
}

func (s *WaitlistService) CheckMatches(clinicID, doctorID uint, startTime time.Time) ([]domain.Waitlist, error) {
	matches, err := s.waitlistRepo.FindPotentialMatches(clinicID, doctorID)
	if err != nil {
		return nil, err
	}

	hour := startTime.Hour()
	timeRange := "tarde"
	if hour < 12 {
		timeRange = "manhã"
	}

	weekdays := map[time.Weekday]string{
		time.Sunday:    "domingo",
		time.Monday:    "segunda",
		time.Tuesday:   "terça",
		time.Wednesday: "quarta",
		time.Thursday:  "quinta",
		time.Friday:    "sexta",
		time.Saturday:  "sábado",
	}
	dayOfWeek := weekdays[startTime.Weekday()]

	var filtered []domain.Waitlist
	for _, w := range matches {
		prefTime := strings.ToLower(w.PreferredTimeRange)
		if prefTime != "" && prefTime != "qualquer" && prefTime != timeRange {
			continue
		}

		prefDays := strings.ToLower(w.PreferredDays)
		if prefDays != "" && prefDays != "[]" && prefDays != "\"\"" {
			if !strings.Contains(prefDays, dayOfWeek) {
				continue
			}
		}

		filtered = append(filtered, w)
	}

	sort.SliceStable(filtered, func(i, j int) bool {
		return urgencyWeight(filtered[i].UrgencyLevel) < urgencyWeight(filtered[j].UrgencyLevel)
	})

	return filtered, nil
}

func urgencyWeight(urgency string) int {
	switch strings.ToLower(urgency) {
	case "high":
		return 1
	case "medium":
		return 2
	case "low":
		return 3
	default:
		return 4
	}
}
