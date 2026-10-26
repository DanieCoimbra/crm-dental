package crm_data.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.catalog.service.ProcedureTypeLookup;
import crm_data.clinic.service.ClinicZones;
import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.scheduling.service.AppointmentService.AppointmentView;
import crm_data.scheduling.service.AppointmentTransitions.Action;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class AppointmentStatusServiceTest {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
	/** 2026-11-03 12:00 em São Paulo. */
	private static final Instant NOW = Instant.parse("2026-11-03T15:00:00Z");

	private final AppointmentRepository appointments = mock(AppointmentRepository.class);
	private final PatientLookup patients = mock(PatientLookup.class);
	private final ClinicZones zones = mock(ClinicZones.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final ProcedureTypeLookup procedures = mock(ProcedureTypeLookup.class);
	private final AppointmentStatusService service = new AppointmentStatusService(appointments, patients, zones, events,
			Clock.fixed(NOW, ZoneOffset.UTC), procedures);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID patientId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		login(Role.DENTIST_ADMIN);
		when(zones.zone()).thenReturn(ZONE);
		when(appointments.saveAndFlush(any(Appointment.class))).thenAnswer(call -> call.getArgument(0));
		when(patients.briefs(anyCollection())).thenReturn(Map.of(patientId, new PatientBrief(patientId, "Maria Souza", "119")));
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private void login(Role role) {
		TenantContext.set(clinicId, userId, role);
	}

	private Appointment appointment(AppointmentStatus status, Instant start, long version) {
		Appointment appointment = new Appointment(UUID.randomUUID(), patientId, start, start.plusSeconds(3600), null, userId);
		appointment.setStatus(status);
		ReflectionTestUtils.setField(appointment, "version", version);
		when(appointments.findById(appointment.getId())).thenReturn(Optional.of(appointment));
		return appointment;
	}

	private static final Instant LATER_TODAY = Instant.parse("2026-11-03T18:00:00Z");
	private static final Instant EARLIER_TODAY = Instant.parse("2026-11-03T12:00:00Z");

	private void assertRule(Runnable action, ProblemTypes type, String reason) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(type);
			assertThat(ex.reason()).isEqualTo(reason);
		});
	}

	private BiFunction<UUID, Long, AppointmentView> call(Action action) {
		return switch (action) {
			case CONFIRM -> service::confirm;
			case CANCEL -> service::cancel;
			case NO_SHOW -> service::noShow;
			case START -> service::start;
			case FINISH -> service::finish;
			case REACTIVATE -> service::reactivate;
		};
	}

	/** Um status de origem permitido e um horário que satisfaz as regras extras de cada ação. */
	private Appointment readyFor(Action action) {
		AppointmentStatus from = AppointmentTransitions.sources(action).iterator().next();
		return appointment(from, action == Action.NO_SHOW ? EARLIER_TODAY : LATER_TODAY, 3);
	}

	@ParameterizedTest
	@EnumSource(Action.class)
	void eachActionMovesTheStatusToItsTargetPublishesTheEventAndReturnsTheBrief(Action action) {
		Appointment appointment = readyFor(action);
		AppointmentStatus from = appointment.getStatus();

		AppointmentView result = call(action).apply(appointment.getId(), 3L);

		assertThat(result.appointment().getStatus()).isEqualTo(AppointmentTransitions.target(action));
		assertThat(result.patient().name()).isEqualTo("Maria Souza");
		verify(appointments).saveAndFlush(appointment);
		verify(events).publishEvent(new SchedulingEvents.StatusChanged(clinicId, appointment.getId(), from.name(),
				AppointmentTransitions.target(action).name(), userId));
	}

	@ParameterizedTest
	@EnumSource(Action.class)
	void anyStatusOutsideTheSourcesIs409WithTheCurrentStatusAndNothingIsSaved(Action action) {
		for (AppointmentStatus status : AppointmentStatus.values()) {
			if (AppointmentTransitions.allowedFrom(action, status)) {
				continue;
			}
			Appointment appointment = appointment(status, LATER_TODAY, 0);

			assertThatThrownBy(() -> call(action).apply(appointment.getId(), 0L)).isInstanceOfSatisfying(BusinessException.class, ex -> {
				assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
				assertThat(ex.getMessage()).contains(status.name());
			});
		}
		verify(appointments, never()).saveAndFlush(any());
		verifyNoInteractions(events);
	}

	@ParameterizedTest
	@EnumSource(Action.class)
	void staleVersionIs409UnknownIdIs404(Action action) {
		Appointment appointment = readyFor(action);
		UUID unknown = UUID.randomUUID();
		when(appointments.findById(unknown)).thenReturn(Optional.empty());

		assertRule(() -> call(action).apply(appointment.getId(), 2L), ProblemTypes.CONFLICT, null);
		assertRule(() -> call(action).apply(unknown, 0L), ProblemTypes.NOT_FOUND, null);
		verify(appointments, never()).saveAndFlush(any());
	}

	@ParameterizedTest
	@EnumSource(value = Action.class, names = { "START", "FINISH", "REACTIVATE" })
	void receptionistIsForbiddenOnDentistActionsBeforeAnyRead(Action action) {
		login(Role.RECEPTIONIST);

		assertRule(() -> call(action).apply(UUID.randomUUID(), 0L), ProblemTypes.FORBIDDEN, null);

		verifyNoInteractions(appointments, events);
	}

	@ParameterizedTest
	@EnumSource(value = Action.class, names = { "CONFIRM", "CANCEL", "NO_SHOW" })
	void receptionistMayConfirmCancelAndMarkNoShow(Action action) {
		login(Role.RECEPTIONIST);
		Appointment appointment = readyFor(action);

		assertThat(call(action).apply(appointment.getId(), 3L).appointment().getStatus()).isEqualTo(AppointmentTransitions.target(action));
	}

	@Test
	void noShowBeforeTheStartIs422NotStartedAndExactlyAtTheStartIsAllowed() {
		Appointment future = appointment(AppointmentStatus.CONFIRMED, LATER_TODAY, 0);
		Appointment atTheStart = appointment(AppointmentStatus.SCHEDULED, NOW, 0);

		assertRule(() -> service.noShow(future.getId(), 0L), ProblemTypes.BUSINESS_RULE, "NOT_STARTED");

		assertThat(service.noShow(atTheStart.getId(), 0L).appointment().getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
		assertThat(future.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
	}

	@Test
	void startOnlyOnTheLocalDayOfTheAppointment() {
		Appointment tomorrow = appointment(AppointmentStatus.CONFIRMED, Instant.parse("2026-11-04T13:00:00Z"), 0);
		Appointment yesterday = appointment(AppointmentStatus.CONFIRMED, Instant.parse("2026-11-02T13:00:00Z"), 0);
		// 01:30Z do dia 04 ainda é 22:30 do dia 03 em São Paulo: é "hoje" na hora local
		Appointment lateTonightLocal = appointment(AppointmentStatus.CONFIRMED, Instant.parse("2026-11-04T01:30:00Z"), 0);

		assertRule(() -> service.start(tomorrow.getId(), 0L), ProblemTypes.BUSINESS_RULE, "NOT_TODAY");
		assertRule(() -> service.start(yesterday.getId(), 0L), ProblemTypes.BUSINESS_RULE, "NOT_TODAY");

		assertThat(service.start(lateTonightLocal.getId(), 0L).appointment().getStatus()).isEqualTo(AppointmentStatus.IN_PROGRESS);
	}

	@Test
	void startRefusesWhenAnotherAppointmentIsInProgress() {
		Appointment appointment = appointment(AppointmentStatus.CONFIRMED, LATER_TODAY, 0);
		when(appointments.existsByStatusAndIdNot(AppointmentStatus.IN_PROGRESS, appointment.getId())).thenReturn(true);

		assertThatThrownBy(() -> service.start(appointment.getId(), 0L)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
			assertThat(ex.getMessage()).isEqualTo("Já existe um atendimento em andamento.");
		});
		assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
		verify(appointments, never()).saveAndFlush(any());
	}

	@Test
	void reactivateDoesNotReapplyTheHourOrPastRulesAndDoesNotReadTheSchedule() {
		Appointment longAgo = appointment(AppointmentStatus.CANCELED, Instant.parse("2020-01-04T02:00:00Z"), 0);

		assertThat(service.reactivate(longAgo.getId(), 0L).appointment().getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
	}

	@Test
	void reactivateAndStatusChangesSurfaceDatabaseConflictsFromTheFlush() {
		Appointment canceled = appointment(AppointmentStatus.CANCELED, LATER_TODAY, 0);
		DataIntegrityViolationException conflict = new DataIntegrityViolationException("23P01");
		when(appointments.saveAndFlush(any(Appointment.class))).thenThrow(conflict);

		assertThatThrownBy(() -> service.reactivate(canceled.getId(), 0L)).isSameAs(conflict);
		verifyNoInteractions(events);
	}

	@Test
	void theLosingTransactionOfASlotRaceOnReactivateIsTheSlotConflict() {
		Appointment canceled = appointment(AppointmentStatus.CANCELED, LATER_TODAY, 0);
		when(appointments.saveAndFlush(any(Appointment.class)))
				.thenThrow(new CannotAcquireLockException("deadlock", new java.sql.SQLException("x", "40P01")));

		assertThatThrownBy(() -> service.reactivate(canceled.getId(), 0L)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.getMessage()).isEqualTo("Horário já ocupado."));
	}
}
