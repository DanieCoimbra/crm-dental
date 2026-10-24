package crm_data.scheduling.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import crm_data.patient.service.PatientEvents;
import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import lombok.RequiredArgsConstructor;

/**
 * Inativar um paciente libera a agenda dele. Escuta o evento <strong>síncrono</strong> do módulo {@code patient} (é
 * {@code @EventListener}, não {@code @TransactionalEventListener}: precisa rodar dentro da transação da inativação e
 * propagar a exceção para reverter tudo). Com um atendimento em andamento recusa (409); senão cancela, entidade a
 * entidade (cada {@code UPDATE} gera sua linha de auditoria, com o dentista do token como autor), os agendamentos
 * futuros em aberto. Os passados, finalizados, cancelados e faltas não são tocados. Reativar o paciente não os restaura.
 */
@Component
@RequiredArgsConstructor
class PatientDeactivationListener {

	private final AppointmentRepository appointments;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	@EventListener
	void on(PatientEvents.Deactivating event) {
		if (appointments.existsByPatientIdAndStatus(event.patientId(), AppointmentStatus.IN_PROGRESS)) {
			throw new BusinessException(ProblemTypes.CONFLICT, "Finalize o atendimento antes de inativar o paciente.");
		}
		Instant now = clock.instant();
		List<Appointment> open = appointments.findOpenFutureByPatient(event.patientId(), now);
		if (open.isEmpty()) {
			return;
		}
		open.forEach(appointment -> appointment.setStatus(AppointmentStatus.CANCELED));
		appointments.saveAllAndFlush(open);
		events.publishEvent(new SchedulingEvents.AutoCanceled(event.clinicId(), event.patientId(), open.size()));
	}
}
