package crm_data.scheduling.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registro de auditoria dos eventos da agenda: somente identificadores e contagens, nunca nome de paciente, telefone ou
 * observação. Roda depois do commit: um rollback não deixa log de algo que não aconteceu.
 */
@Component
class SchedulingAuditLogger {

	private static final Logger log = LoggerFactory.getLogger("crm_data.scheduling.audit");

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(SchedulingEvents.BusinessHoursChanged event) {
		log.info("Horário comercial alterado: clinicId={} userId={} afetados={}", event.clinicId(), event.userId(),
				event.affectedCount());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(SchedulingEvents.ClinicHolidayChanged event) {
		log.info("Feriado da clínica {}: clinicId={} userId={} holidayId={} afetados={}", event.action(), event.clinicId(),
				event.userId(), event.holidayId(), event.affectedCount());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(SchedulingEvents.AppointmentCreated event) {
		log.info("Agendamento criado: clinicId={} appointmentId={} patientId={} userId={}", event.clinicId(),
				event.appointmentId(), event.patientId(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(SchedulingEvents.AppointmentRescheduled event) {
		log.info("Agendamento remarcado: clinicId={} appointmentId={} userId={}", event.clinicId(), event.appointmentId(),
				event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(SchedulingEvents.StatusChanged event) {
		log.info("Agendamento {} -> {}: clinicId={} appointmentId={} userId={}", event.from(), event.to(), event.clinicId(),
				event.appointmentId(), event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(SchedulingEvents.AutoCanceled event) {
		log.info("Agendamentos cancelados pela inativação do paciente: clinicId={} patientId={} cancelados={}", event.clinicId(),
				event.patientId(), event.count());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void on(SchedulingEvents.OutsideHoursBooked event) {
		log.info("Encaixe fora do expediente: clinicId={} appointmentId={} userId={}", event.clinicId(), event.appointmentId(),
				event.userId());
	}
}
