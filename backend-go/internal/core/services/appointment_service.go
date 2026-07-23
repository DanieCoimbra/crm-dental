package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
	"time"
)

type AppointmentService struct {
	appointmentRepo  *repositories.AppointmentRepository
	apptTypeRepo     *repositories.AppointmentTypeRepository
	settingService   *SettingService
	inventoryService *InventoryService
}

func NewAppointmentService() *AppointmentService {
	return &AppointmentService{
		appointmentRepo:  repositories.NewAppointmentRepository(),
		apptTypeRepo:     repositories.NewAppointmentTypeRepository(),
		settingService:   NewSettingService(),
		inventoryService: NewInventoryService(),
	}
}

func (s *AppointmentService) CreateAppointment(clinicID, doctorID, patientID uint, roomID, apptTypeID *uint, startTime, endTime *time.Time, notes string) (*domain.Appointment, error) {
	if doctorID == 0 || patientID == 0 {
		return nil, errors.New("doutor e paciente são obrigatórios")
	}

	if startTime != nil && startTime.Before(time.Now()) {
		return nil, errors.New("não é possível agendar no passado")
	}

	if apptTypeID != nil && startTime != nil {
		apptType, err := s.apptTypeRepo.FindByID(*apptTypeID, clinicID)
		if err == nil {
			calcEndTime := startTime.Add(time.Duration(apptType.DurationMinutes) * time.Minute)
			endTime = &calcEndTime
		}
	}

	if startTime != nil && endTime != nil {
		overlap, err := s.appointmentRepo.HasOverlap(clinicID, doctorID, nil, *startTime, *endTime)
		if err != nil {
			return nil, errors.New("erro ao verificar conflito de horário")
		}
		if overlap {
			return nil, errors.New("o médico já possui um agendamento neste horário")
		}

		dateStr := startTime.Format("2006-01-02")
		holidays := s.settingService.GetHolidaysForYear(clinicID, startTime.Year())
		for _, h := range holidays {
			if h.Date == dateStr {
				return nil, errors.New("não é possível agendar em feriados da clínica")
			}
		}

		allowWeekends := s.settingService.GetSetting(clinicID, "allow_weekends", "false")
		if allowWeekends == "false" {
			wk := startTime.Weekday()
			if wk == time.Saturday || wk == time.Sunday {
				return nil, errors.New("a clínica não funciona aos finais de semana")
			}
		}

		businessStart := s.settingService.GetSetting(clinicID, "business_start_hour", "08:00")
		businessEnd := s.settingService.GetSetting(clinicID, "business_end_hour", "18:00")

		bStart, err1 := time.Parse("15:04", businessStart)
		bEnd, err2 := time.Parse("15:04", businessEnd)

		if err1 == nil && err2 == nil {
			startMin := startTime.Hour()*60 + startTime.Minute()
			endMin := endTime.Hour()*60 + endTime.Minute()
			bStartMin := bStart.Hour()*60 + bStart.Minute()
			bEndMin := bEnd.Hour()*60 + bEnd.Minute()

			if startMin < bStartMin || endMin > bEndMin {
				return nil, errors.New("o agendamento está fora do horário de funcionamento")
			}
		}
	}

	appointment := &domain.Appointment{
		ClinicID:          clinicID,
		DoctorID:          doctorID,
		PatientID:         patientID,
		RoomID:            roomID,
		AppointmentTypeID: apptTypeID,
		StartTime:         startTime,
		EndTime:           endTime,
		Notes:             notes,
		Status:            "scheduled",
	}

	err := s.appointmentRepo.Create(appointment)
	if err != nil {
		return nil, err
	}
	return appointment, nil
}

func (s *AppointmentService) ListAppointments(clinicID uint, start string, end string) ([]domain.Appointment, error) {
	return s.appointmentRepo.ListByClinic(clinicID, start, end)
}

func (s *AppointmentService) DeleteAppointment(clinicID, id, deletedBy uint) error {
	return s.appointmentRepo.Delete(id, clinicID, deletedBy)
}

func (s *AppointmentService) UpdateAppointment(clinicID, id, doctorID, patientID uint, roomID, apptTypeID *uint, startTime, endTime *time.Time, notes string) (*domain.Appointment, error) {
	appt, err := s.appointmentRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("agendamento não encontrado")
	}

	if startTime != nil && startTime.Before(time.Now()) {
		return nil, errors.New("não é possível agendar no passado")
	}

	if apptTypeID != nil && startTime != nil {
		apptType, err := s.apptTypeRepo.FindByID(*apptTypeID, clinicID)
		if err == nil {
			calcEndTime := startTime.Add(time.Duration(apptType.DurationMinutes) * time.Minute)
			endTime = &calcEndTime
		}
	}

	if startTime != nil && endTime != nil {
		overlap, err := s.appointmentRepo.HasOverlap(clinicID, doctorID, &id, *startTime, *endTime)
		if err != nil {
			return nil, errors.New("erro ao verificar conflito de horário")
		}
		if overlap {
			return nil, errors.New("o médico já possui um agendamento neste horário")
		}

		dateStr := startTime.Format("2006-01-02")
		holidays := s.settingService.GetHolidaysForYear(clinicID, startTime.Year())
		for _, h := range holidays {
			if h.Date == dateStr {
				return nil, errors.New("não é possível agendar em feriados da clínica")
			}
		}

		allowWeekends := s.settingService.GetSetting(clinicID, "allow_weekends", "false")
		if allowWeekends == "false" {
			wk := startTime.Weekday()
			if wk == time.Saturday || wk == time.Sunday {
				return nil, errors.New("a clínica não funciona aos finais de semana")
			}
		}

		businessStart := s.settingService.GetSetting(clinicID, "business_start_hour", "08:00")
		businessEnd := s.settingService.GetSetting(clinicID, "business_end_hour", "18:00")

		bStart, err1 := time.Parse("15:04", businessStart)
		bEnd, err2 := time.Parse("15:04", businessEnd)

		if err1 == nil && err2 == nil {
			startMin := startTime.Hour()*60 + startTime.Minute()
			endMin := endTime.Hour()*60 + endTime.Minute()
			bStartMin := bStart.Hour()*60 + bStart.Minute()
			bEndMin := bEnd.Hour()*60 + bEnd.Minute()

			if startMin < bStartMin || endMin > bEndMin {
				return nil, errors.New("o agendamento está fora do horário de funcionamento")
			}
		}
	}

	appt.DoctorID = doctorID
	appt.PatientID = patientID
	appt.RoomID = roomID
	appt.AppointmentTypeID = apptTypeID
	appt.StartTime = startTime
	appt.EndTime = endTime
	appt.Notes = notes

	if err := s.appointmentRepo.Update(appt); err != nil {
		return nil, err
	}
	return appt, nil
}

func (s *AppointmentService) StartAppointment(clinicID, id uint) (*domain.Appointment, error) {
	appt, err := s.appointmentRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("agendamento não encontrado")
	}
	inProgress, err := s.appointmentRepo.HasAppointmentInProgress(clinicID, appt.DoctorID)
	if err != nil {
		return nil, errors.New("erro ao verificar agendamentos em andamento")
	}
	if inProgress {
		return nil, errors.New("o médico já possui um agendamento em andamento")
	}
	now := time.Now()
	appt.Status = "in_progress"
	appt.ActualStartTime = &now
	if err := s.appointmentRepo.Update(appt); err != nil {
		return nil, err
	}
	return appt, nil
}

func (s *AppointmentService) FinishAppointment(clinicID, id uint) (*domain.Appointment, error) {
	appt, err := s.appointmentRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("agendamento não encontrado")
	}
	now := time.Now()
	appt.Status = "finished"
	appt.ActualEndTime = &now
	if err := s.appointmentRepo.Update(appt); err != nil {
		return nil, err
	}

	// [F05 Trigger] Dedução autônoma do estoque via goroutine (Assíncrono)
	if appt.AppointmentTypeID != nil {
		go s.inventoryService.ProcessAppointmentMaterials(clinicID, *appt.AppointmentTypeID)
	}

	return appt, nil
}
