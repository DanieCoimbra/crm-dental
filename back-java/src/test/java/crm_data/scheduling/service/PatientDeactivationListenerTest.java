package crm_data.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import crm_data.patient.service.PatientEvents;
import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;

class PatientDeactivationListenerTest {

	private static final Instant NOW = Instant.parse("2026-11-03T15:00:00Z");

	private final AppointmentRepository appointments = mock(AppointmentRepository.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final PatientDeactivationListener listener = new PatientDeactivationListener(appointments, events,
			Clock.fixed(NOW, ZoneOffset.UTC));

	private final UUID clinicId = UUID.randomUUID();
	private final UUID patientId = UUID.randomUUID();
	private final PatientEvents.Deactivating event = new PatientEvents.Deactivating(clinicId, patientId);

	private Appointment open(AppointmentStatus status) {
		Appointment appointment = new Appointment(UUID.randomUUID(), patientId, NOW.plusSeconds(86_400), NOW.plusSeconds(90_000), null,
				UUID.randomUUID());
		appointment.setStatus(status);
		return appointment;
	}

	@Test
	void anAppointmentInProgressRefusesTheInactivationBeforeCancelingAnything() {
		when(appointments.existsByPatientIdAndStatus(patientId, AppointmentStatus.IN_PROGRESS)).thenReturn(true);

		assertThatThrownBy(() -> listener.on(event)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
			assertThat(ex.getMessage()).isEqualTo("Finalize o atendimento antes de inativar o paciente.");
		});

		verify(appointments, never()).findOpenFutureByPatient(any(), any());
		verify(appointments, never()).saveAllAndFlush(any());
		verifyNoInteractions(events);
	}

	@Test
	void cancelsEveryFutureOpenAppointmentInOneFlushAndPublishesOnlyTheCount() {
		Appointment scheduled = open(AppointmentStatus.SCHEDULED);
		Appointment confirmed = open(AppointmentStatus.CONFIRMED);
		when(appointments.findOpenFutureByPatient(patientId, NOW)).thenReturn(List.of(scheduled, confirmed));

		listener.on(event);

		assertThat(scheduled.getStatus()).isEqualTo(AppointmentStatus.CANCELED);
		assertThat(confirmed.getStatus()).isEqualTo(AppointmentStatus.CANCELED);
		verify(appointments).saveAllAndFlush(List.of(scheduled, confirmed));
		verify(events).publishEvent(new SchedulingEvents.AutoCanceled(clinicId, patientId, 2));
	}

	@Test
	void nothingOpenMeansNoWriteAndNoEvent() {
		when(appointments.findOpenFutureByPatient(patientId, NOW)).thenReturn(List.of());

		listener.on(event);

		verify(appointments, never()).saveAllAndFlush(any());
		verifyNoInteractions(events);
	}

	@Test
	void aFailureWhileCancelingPropagatesSoTheInactivationIsRolledBack() {
		Appointment scheduled = open(AppointmentStatus.SCHEDULED);
		when(appointments.findOpenFutureByPatient(patientId, NOW)).thenReturn(List.of(scheduled));
		IllegalStateException failure = new IllegalStateException("falha");
		when(appointments.saveAllAndFlush(any())).thenThrow(failure);

		assertThatThrownBy(() -> listener.on(event)).isSameAs(failure);

		verifyNoInteractions(events);
	}
}
