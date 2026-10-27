package crm_data.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;

class AppointmentLookupServiceTest {

	private final AppointmentRepository appointments = mock(AppointmentRepository.class);
	private final AppointmentLookupService lookup = new AppointmentLookupService(appointments);

	private final UUID patientId = UUID.randomUUID();
	private final Appointment appointment = new Appointment(UUID.randomUUID(), patientId, Instant.parse("2026-11-03T13:00:00Z"),
			Instant.parse("2026-11-03T14:00:00Z"), null, UUID.randomUUID());

	private void assertError(Runnable action, ProblemTypes type, String message) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(type);
			assertThat(ex.getMessage()).isEqualTo(message);
		});
	}

	@Test
	void requireOfPatientAcceptsTheirAppointmentWithoutLocking() {
		when(appointments.findOwnById(appointment.getId())).thenReturn(Optional.of(appointment));

		assertThatCode(() -> lookup.requireOfPatient(appointment.getId(), patientId)).doesNotThrowAnyException();

		verify(appointments, never()).findForUpdateById(appointment.getId());
	}

	@Test
	void lockOfPatientReadsWithTheRowLock() {
		when(appointments.findForUpdateById(appointment.getId())).thenReturn(Optional.of(appointment));

		assertThatCode(() -> lookup.lockOfPatient(appointment.getId(), patientId)).doesNotThrowAnyException();

		verify(appointments).findForUpdateById(appointment.getId());
		verify(appointments, never()).findOwnById(appointment.getId());
	}

	@Test
	void unknownAppointmentIsNotFoundForBothChecks() {
		UUID unknown = UUID.randomUUID();
		when(appointments.findOwnById(unknown)).thenReturn(Optional.empty());
		when(appointments.findForUpdateById(unknown)).thenReturn(Optional.empty());

		assertError(() -> lookup.requireOfPatient(unknown, patientId), ProblemTypes.NOT_FOUND, "Agendamento não encontrado.");
		assertError(() -> lookup.lockOfPatient(unknown, patientId), ProblemTypes.NOT_FOUND, "Agendamento não encontrado.");
	}

	@Test
	void anAppointmentOfAnotherPatientIsConflictForBothChecks() {
		when(appointments.findOwnById(appointment.getId())).thenReturn(Optional.of(appointment));
		when(appointments.findForUpdateById(appointment.getId())).thenReturn(Optional.of(appointment));
		UUID other = UUID.randomUUID();

		assertError(() -> lookup.requireOfPatient(appointment.getId(), other), ProblemTypes.CONFLICT, "O atendimento é de outro paciente.");
		assertError(() -> lookup.lockOfPatient(appointment.getId(), other), ProblemTypes.CONFLICT, "O atendimento é de outro paciente.");
	}
}
