package crm_data.scheduling.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class AppointmentLookupService implements AppointmentLookup {

	private final AppointmentRepository appointments;

	@Override
	@Transactional(readOnly = true)
	public void requireOfPatient(UUID appointmentId, UUID patientId) {
		check(appointments.findOwnById(appointmentId).orElse(null), patientId);
	}

	@Override
	@Transactional
	public void lockOfPatient(UUID appointmentId, UUID patientId) {
		check(appointments.findForUpdateById(appointmentId).orElse(null), patientId);
	}

	private static void check(Appointment appointment, UUID patientId) {
		if (appointment == null) {
			throw new BusinessException(ProblemTypes.NOT_FOUND, "Agendamento não encontrado.");
		}
		if (!appointment.getPatientId().equals(patientId)) {
			throw new BusinessException(ProblemTypes.CONFLICT, "O atendimento é de outro paciente.");
		}
	}
}
