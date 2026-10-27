package crm_data.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.catalog.service.ProcedureBrief;
import crm_data.catalog.service.ProcedureTypeLookup;
import crm_data.clinic.service.ClinicZones;
import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.scheduling.service.AppointmentService.AppointmentView;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class AppointmentServiceTest {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
	/** Sábado 2026-10-24, 12:00 em São Paulo. */
	private static final Instant NOW = Instant.parse("2026-10-24T15:00:00Z");
	/** Segunda-feira. */
	private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

	private final AppointmentRepository appointments = mock(AppointmentRepository.class);
	private final PatientLookup patients = mock(PatientLookup.class);
	private final ClinicZones zones = mock(ClinicZones.class);
	private final BusinessHoursService businessHours = mock(BusinessHoursService.class);
	private final HolidayService holidays = mock(HolidayService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final ProcedureTypeLookup procedures = mock(ProcedureTypeLookup.class);
	private final AppointmentService service = new AppointmentService(appointments, patients, zones, businessHours, holidays,
			events, Clock.fixed(NOW, ZoneOffset.UTC), procedures);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID patientId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		login(Role.RECEPTIONIST);
		when(zones.zone()).thenReturn(ZONE);
		Map<DayOfWeek, DaySchedule> schedule = new EnumMap<>(DayOfWeek.class);
		DaySchedule weekday = new DaySchedule(true, LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(12, 0), LocalTime.of(13, 0));
		for (DayOfWeek day : new DayOfWeek[] { DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY }) {
			schedule.put(day, weekday);
		}
		when(businessHours.weekSchedule()).thenReturn(schedule);
		when(holidays.holidayDates(any(), any())).thenReturn(Set.of());
		when(appointments.saveAndFlush(any(Appointment.class))).thenAnswer(call -> call.getArgument(0));
		when(patients.briefs(anyCollection())).thenReturn(Map.of(patientId, new PatientBrief(patientId, "Maria Souza", "11987654321")));
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private void login(Role role) {
		TenantContext.set(clinicId, userId, role);
	}

	private static Instant at(LocalDate date, int hour, int minute) {
		return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(ZONE).toInstant();
	}

	private Appointment existing(AppointmentStatus status, Instant start, long version) {
		Appointment appointment = new Appointment(UUID.randomUUID(), patientId, start, start.plusSeconds(3600), "obs", userId);
		appointment.setStatus(status);
		ReflectionTestUtils.setField(appointment, "version", version);
		return appointment;
	}

	private void assertRule(Runnable action, ProblemTypes type, String reason) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(type);
			assertThat(ex.reason()).isEqualTo(reason);
		});
	}

	// ---- criar ----

	@ParameterizedTest
	@CsvSource({ "RECEPTIONIST", "DENTIST_ADMIN" })
	void createSavesAScheduledAppointmentWithTheTokenUserAndReturnsTheBrief(Role role) {
		login(role);

		AppointmentView created = service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 9, 45), "Retorno", false);

		ArgumentCaptor<Appointment> saved = ArgumentCaptor.forClass(Appointment.class);
		verify(appointments).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
		assertThat(saved.getValue().getPatientId()).isEqualTo(patientId);
		assertThat(saved.getValue().getCreatedBy()).isEqualTo(userId);
		assertThat(saved.getValue().getNotes()).isEqualTo("Retorno");
		assertThat(created.patient().name()).isEqualTo("Maria Souza");
		verify(patients).requireSchedulable(patientId);
		verify(events).publishEvent(new SchedulingEvents.AppointmentCreated(clinicId, saved.getValue().getId(), patientId, userId));
		verify(events, never()).publishEvent(any(SchedulingEvents.OutsideHoursBooked.class));
	}

	@Test
	void receptionistCannotFitAndNothingElseRuns() {
		assertRule(() -> service.create(patientId, at(MONDAY, 19, 0), at(MONDAY, 20, 0), null, true), ProblemTypes.FORBIDDEN, null);

		verifyNoInteractions(patients, appointments, events);
	}

	@Test
	void anInvalidPatientStopsBeforeAnyRuleOrWrite() {
		doThrow(new BusinessException(ProblemTypes.CONFLICT, "Paciente inativo.")).when(patients).requireSchedulable(patientId);

		assertThatThrownBy(() -> service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 10, 0), null, false))
				.isInstanceOf(BusinessException.class);

		verifyNoInteractions(appointments, events);
	}

	@ParameterizedTest
	@CsvSource({ "7,30,8,30,OUTSIDE_HOURS", "11,30,12,30,LUNCH", "17,30,18,30,OUTSIDE_HOURS", "12,0,12,45,LUNCH" })
	void receptionistIsBlockedOutsideTheWorkingHoursWithTheReason(int fromH, int fromM, int toH, int toM, String reason) {
		assertRule(() -> service.create(patientId, at(MONDAY, fromH, fromM), at(MONDAY, toH, toM), null, false),
				ProblemTypes.BUSINESS_RULE, reason);

		verify(appointments, never()).saveAndFlush(any());
	}

	@Test
	void receptionistIsBlockedOnAClosedDayAndOnAHoliday() {
		LocalDate sunday = MONDAY.plusDays(6);
		assertRule(() -> service.create(patientId, at(sunday, 9, 0), at(sunday, 10, 0), null, false), ProblemTypes.BUSINESS_RULE,
				"CLOSED_DAY");

		when(holidays.holidayDates(any(), any())).thenReturn(Set.of(MONDAY));
		assertRule(() -> service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 10, 0), null, false), ProblemTypes.BUSINESS_RULE,
				"HOLIDAY");
	}

	@Test
	void receptionistIsBlockedInThePastButTheFiveMinuteToleranceHolds() {
		LocalDate friday = LocalDate.of(2026, 10, 23);

		assertRule(() -> service.create(patientId, at(friday, 9, 0), at(friday, 10, 0), null, false), ProblemTypes.BUSINESS_RULE, "PAST");

		Instant justInside = NOW.minusSeconds(5 * 60);
		// 12:00 local de sábado está fechado: o passado passa, mas o dia fechado bloqueia (prova que PAST não foi o motivo)
		assertRule(() -> service.create(patientId, justInside, justInside.plusSeconds(1800), null, false), ProblemTypes.BUSINESS_RULE,
				"CLOSED_DAY");
	}

	@Test
	void dentistMayRegisterInThePastWithoutFitting() {
		login(Role.DENTIST_ADMIN);
		LocalDate friday = LocalDate.of(2026, 10, 23);

		service.create(patientId, at(friday, 9, 0), at(friday, 10, 0), null, false);

		verify(appointments).saveAndFlush(any(Appointment.class));
		verify(events, never()).publishEvent(any(SchedulingEvents.OutsideHoursBooked.class));
	}

	@Test
	void dentistIsBlockedByTheHoursUnlessHeFitsAndTheFitIsTraced() {
		login(Role.DENTIST_ADMIN);
		assertRule(() -> service.create(patientId, at(MONDAY, 19, 0), at(MONDAY, 20, 0), null, false), ProblemTypes.BUSINESS_RULE,
				"OUTSIDE_HOURS");

		service.create(patientId, at(MONDAY, 19, 0), at(MONDAY, 20, 0), null, true);

		ArgumentCaptor<Appointment> saved = ArgumentCaptor.forClass(Appointment.class);
		verify(appointments).saveAndFlush(saved.capture());
		verify(events).publishEvent(new SchedulingEvents.OutsideHoursBooked(clinicId, saved.getValue().getId(), userId));
	}

	@Test
	void fittingInsideTheHoursIsNotTraced() {
		login(Role.DENTIST_ADMIN);

		service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 10, 0), null, true);

		verify(events, never()).publishEvent(any(SchedulingEvents.OutsideHoursBooked.class));
	}

	@Test
	void theDatabaseConflictSurfacesFromTheExplicitFlush() {
		DataIntegrityViolationException conflict = new DataIntegrityViolationException("23P01");
		when(appointments.saveAndFlush(any(Appointment.class))).thenThrow(conflict);

		assertThatThrownBy(() -> service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 10, 0), null, false)).isSameAs(conflict);

		verifyNoInteractions(events);
	}

	private static org.springframework.dao.CannotAcquireLockException lockFailure(String sqlState) {
		return new org.springframework.dao.CannotAcquireLockException("falha de lock", new java.sql.SQLException("detalhe 52998224725", sqlState));
	}

	@ParameterizedTest
	@CsvSource({ "40P01", "40001" })
	void theLosingTransactionOfASlotRaceIsTheSlotConflictNotA500(String sqlState) {
		when(appointments.saveAndFlush(any(Appointment.class))).thenThrow(lockFailure(sqlState));

		assertThatThrownBy(() -> service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 10, 0), null, false))
				.isInstanceOfSatisfying(BusinessException.class, ex -> {
					assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
					assertThat(ex.getMessage()).isEqualTo("Horário já ocupado.");
				});
		verifyNoInteractions(events);
	}

	@Test
	void theSameRaceOnAReschedule() {
		Appointment scheduled = existing(AppointmentStatus.SCHEDULED, at(MONDAY, 9, 0), 0);
		when(appointments.findOwnById(scheduled.getId())).thenReturn(Optional.of(scheduled));
		when(appointments.saveAndFlush(any(Appointment.class))).thenThrow(lockFailure("40P01"));

		assertThatThrownBy(() -> service.reschedule(scheduled.getId(), at(MONDAY, 14, 0), at(MONDAY, 15, 0), null, false, 0L))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));
	}

	@Test
	void otherLockFailuresAreNotDisguisedAsASlotConflict() {
		org.springframework.dao.CannotAcquireLockException timeout = lockFailure("55P03");
		when(appointments.saveAndFlush(any(Appointment.class))).thenThrow(timeout);

		assertThatThrownBy(() -> service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 10, 0), null, false)).isSameAs(timeout);
	}

	// ---- agenda ----

	@Test
	void listUsesTheLocalDayWindowInTheClinicZoneAndTheTwelveHourLookback() {
		when(appointments.findWindow(any(), any(), any())).thenReturn(List.of());

		service.list(MONDAY, MONDAY.plusDays(1), null);

		Instant start = Instant.parse("2026-11-02T03:00:00Z");
		Instant end = Instant.parse("2026-11-04T03:00:00Z");
		verify(appointments).findWindow(start, end, start.minusSeconds(12 * 3600));
	}

	@Test
	void listWithStatusesUsesTheFilteredQuery() {
		when(appointments.findWindowByStatus(any(), any(), any(), anyCollection())).thenReturn(List.of());

		service.list(MONDAY, MONDAY, List.of(AppointmentStatus.CANCELED, AppointmentStatus.NO_SHOW));

		verify(appointments).findWindowByStatus(any(), any(), any(), eqStatuses());
		verify(appointments, never()).findWindow(any(), any(), any());
	}

	private static List<AppointmentStatus> eqStatuses() {
		return org.mockito.ArgumentMatchers.eq(List.of(AppointmentStatus.CANCELED, AppointmentStatus.NO_SHOW));
	}

	@Test
	void listReturnsTheBriefsInBatchAndKeepsTheRepositoryOrder() {
		Appointment first = existing(AppointmentStatus.SCHEDULED, at(MONDAY, 9, 0), 0);
		Appointment second = existing(AppointmentStatus.CONFIRMED, at(MONDAY, 10, 0), 1);
		when(appointments.findWindow(any(), any(), any())).thenReturn(List.of(first, second));

		List<AppointmentView> views = service.list(MONDAY, MONDAY, List.of());

		assertThat(views).extracting(v -> v.appointment().getId()).containsExactly(first.getId(), second.getId());
		assertThat(views).allSatisfy(v -> assertThat(v.patient().name()).isEqualTo("Maria Souza"));
		verify(patients).briefs(List.of(patientId));
	}

	@Test
	void listRejectsAnInvertedWindowAndMoreThan42Days() {
		assertThatThrownBy(() -> service.list(MONDAY, MONDAY.minusDays(1), null)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.VALIDATION));
		assertThatThrownBy(() -> service.list(MONDAY, MONDAY.plusDays(42), null)).isInstanceOf(BusinessException.class);
		when(appointments.findWindow(any(), any(), any())).thenReturn(List.of());
		assertThat(service.list(MONDAY, MONDAY.plusDays(41), null)).as("42 dias inclusivos é o limite").isEmpty();
	}

	@Test
	void getReturnsTheAppointmentOrNotFound() {
		Appointment found = existing(AppointmentStatus.SCHEDULED, at(MONDAY, 9, 0), 0);
		when(appointments.findOwnById(found.getId())).thenReturn(Optional.of(found));
		UUID unknown = UUID.randomUUID();
		when(appointments.findOwnById(unknown)).thenReturn(Optional.empty());

		assertThat(service.get(found.getId()).appointment()).isSameAs(found);
		assertThatThrownBy(() -> service.get(unknown)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}

	// ---- remarcar ----

	@Test
	void rescheduleMovesTheTimeAndAConfirmedOneGoesBackToScheduled() {
		Appointment confirmed = existing(AppointmentStatus.CONFIRMED, at(MONDAY, 9, 0), 3);
		when(appointments.findOwnById(confirmed.getId())).thenReturn(Optional.of(confirmed));

		AppointmentView result = service.reschedule(confirmed.getId(), at(MONDAY, 14, 0), at(MONDAY, 15, 0), "nova obs", false, 3L);

		assertThat(result.appointment().getStartsAt()).isEqualTo(at(MONDAY, 14, 0));
		assertThat(result.appointment().getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
		assertThat(result.appointment().getNotes()).isEqualTo("nova obs");
		verify(events).publishEvent(new SchedulingEvents.AppointmentRescheduled(clinicId, confirmed.getId(), userId));
	}

	@Test
	void editingOnlyTheNotesKeepsTheStatusSkipsTheHourRulesAndDoesNotPublishRescheduled() {
		Appointment confirmed = existing(AppointmentStatus.CONFIRMED, at(MONDAY, 9, 0), 3);
		when(appointments.findOwnById(confirmed.getId())).thenReturn(Optional.of(confirmed));

		service.reschedule(confirmed.getId(), confirmed.getStartsAt(), confirmed.getEndsAt(), null, false, 3L);

		assertThat(confirmed.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
		assertThat(confirmed.getNotes()).as("null limpa a observação").isNull();
		verifyNoInteractions(businessHours, holidays);
		verify(events, never()).publishEvent(any(SchedulingEvents.AppointmentRescheduled.class));
	}

	@Test
	void rescheduleAppliesTheSameRulesToTheNewTime() {
		Appointment scheduled = existing(AppointmentStatus.SCHEDULED, at(MONDAY, 9, 0), 0);
		when(appointments.findOwnById(scheduled.getId())).thenReturn(Optional.of(scheduled));

		assertRule(() -> service.reschedule(scheduled.getId(), at(MONDAY, 12, 0), at(MONDAY, 12, 45), null, false, 0L),
				ProblemTypes.BUSINESS_RULE, "LUNCH");
		assertRule(() -> service.reschedule(scheduled.getId(), at(LocalDate.of(2026, 10, 20), 9, 0), at(LocalDate.of(2026, 10, 20), 10, 0),
				null, false, 0L), ProblemTypes.BUSINESS_RULE, "PAST");

		assertThat(scheduled.getStartsAt()).as("a entidade não é salva com horário inválido").isEqualTo(at(MONDAY, 9, 0));
	}

	@Test
	void dentistFitsOnAReschedule() {
		login(Role.DENTIST_ADMIN);
		Appointment scheduled = existing(AppointmentStatus.SCHEDULED, at(MONDAY, 9, 0), 0);
		when(appointments.findOwnById(scheduled.getId())).thenReturn(Optional.of(scheduled));

		service.reschedule(scheduled.getId(), at(MONDAY, 19, 0), at(MONDAY, 20, 0), null, true, 0L);

		verify(events).publishEvent(new SchedulingEvents.OutsideHoursBooked(clinicId, scheduled.getId(), userId));
	}

	@Test
	void receptionistFittingOnAReschedule_403BeforeLoading() {
		assertRule(() -> service.reschedule(UUID.randomUUID(), at(MONDAY, 19, 0), at(MONDAY, 20, 0), null, true, 0L),
				ProblemTypes.FORBIDDEN, null);

		verifyNoInteractions(appointments);
	}

	@Test
	void staleVersionIsConflictAndUnknownIdIsNotFound() {
		Appointment scheduled = existing(AppointmentStatus.SCHEDULED, at(MONDAY, 9, 0), 4);
		when(appointments.findOwnById(scheduled.getId())).thenReturn(Optional.of(scheduled));
		UUID unknown = UUID.randomUUID();
		when(appointments.findOwnById(unknown)).thenReturn(Optional.empty());

		assertRule(() -> service.reschedule(scheduled.getId(), at(MONDAY, 10, 0), at(MONDAY, 11, 0), null, false, 3L),
				ProblemTypes.CONFLICT, null);
		assertRule(() -> service.reschedule(unknown, at(MONDAY, 10, 0), at(MONDAY, 11, 0), null, false, 0L), ProblemTypes.NOT_FOUND,
				null);
		verify(appointments, never()).saveAndFlush(any());
	}

	@ParameterizedTest
	@CsvSource({ "IN_PROGRESS", "COMPLETED", "CANCELED", "NO_SHOW" })
	void onlyScheduledAndConfirmedCanBeChanged(AppointmentStatus status) {
		Appointment appointment = existing(status, at(MONDAY, 9, 0), 0);
		when(appointments.findOwnById(appointment.getId())).thenReturn(Optional.of(appointment));

		assertThatThrownBy(() -> service.reschedule(appointment.getId(), at(MONDAY, 10, 0), at(MONDAY, 11, 0), null, false, 0L))
				.isInstanceOfSatisfying(BusinessException.class, ex -> {
					assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
					assertThat(ex.getMessage()).contains(status.name());
				});
		verify(appointments, never()).saveAndFlush(any());
	}

	// ---- procedimento (Fase 3, F02) ----

	private final UUID procedureId = UUID.randomUUID();

	private Appointment existingWithProcedure(AppointmentStatus status, UUID procedure) {
		Appointment appointment = existing(status, at(MONDAY, 9, 0), 0);
		appointment.setProcedureTypeId(procedure);
		return appointment;
	}

	@Test
	void createWithAProcedureChecksItBeforeSavingAndStoresIt() {
		when(procedures.briefs(anyCollection())).thenReturn(Map.of(procedureId, new ProcedureBrief(procedureId, "Restauração", "#3B82F6")));

		AppointmentView created = service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 9, 45), null, false, procedureId);

		org.mockito.InOrder order = org.mockito.Mockito.inOrder(procedures, appointments);
		order.verify(procedures).requireActive(procedureId);
		ArgumentCaptor<Appointment> saved = ArgumentCaptor.forClass(Appointment.class);
		order.verify(appointments).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getProcedureTypeId()).isEqualTo(procedureId);
		assertThat(created.procedure().name()).isEqualTo("Restauração");
	}

	@Test
	void createWithoutProcedureNeverTouchesTheCatalogToValidateAndHasANullProcedure() {
		AppointmentView created = service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 9, 45), null, false);

		verify(procedures, never()).requireActive(any());
		assertThat(created.procedure()).isNull();
		ArgumentCaptor<Appointment> saved = ArgumentCaptor.forClass(Appointment.class);
		verify(appointments).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getProcedureTypeId()).isNull();
	}

	@Test
	void anInactiveOrUnknownProcedureStopsTheCreationBeforeAnyWrite() {
		doThrow(new BusinessException(ProblemTypes.BUSINESS_RULE, "O procedimento está inativo.", "PROCEDURE_INACTIVE"))
				.doThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Procedimento não encontrado."))
				.when(procedures).requireActive(procedureId);

		assertRule(() -> service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 9, 45), null, false, procedureId),
				ProblemTypes.BUSINESS_RULE, "PROCEDURE_INACTIVE");
		assertRule(() -> service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 9, 45), null, false, procedureId),
				ProblemTypes.NOT_FOUND, null);

		verify(appointments, never()).saveAndFlush(any());
	}

	@Test
	void theProcedureIsCheckedAfterThePatientSoAMissingPatientKeepsItsOwnError() {
		doThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado.")).when(patients).requireSchedulable(patientId);

		assertThatThrownBy(() -> service.create(patientId, at(MONDAY, 9, 0), at(MONDAY, 9, 45), null, false, procedureId))
				.isInstanceOf(BusinessException.class);

		verifyNoInteractions(procedures);
	}

	@Test
	void rescheduleReplacesTheProcedureAndNullClearsIt() {
		Appointment appointment = existingWithProcedure(AppointmentStatus.SCHEDULED, procedureId);
		when(appointments.findOwnById(appointment.getId())).thenReturn(Optional.of(appointment));
		UUID other = UUID.randomUUID();

		service.reschedule(appointment.getId(), at(MONDAY, 9, 0), at(MONDAY, 10, 0), "obs", false, other, 0L);
		assertThat(appointment.getProcedureTypeId()).isEqualTo(other);
		verify(procedures).requireActive(other);

		service.reschedule(appointment.getId(), at(MONDAY, 9, 0), at(MONDAY, 10, 0), "obs", false, null, 0L);
		assertThat(appointment.getProcedureTypeId()).as("ausente ou null remove o vínculo (D1)").isNull();
	}

	@Test
	void theShortRescheduleOverloadClearsTheProcedure() {
		Appointment appointment = existingWithProcedure(AppointmentStatus.SCHEDULED, procedureId);
		when(appointments.findOwnById(appointment.getId())).thenReturn(Optional.of(appointment));

		service.reschedule(appointment.getId(), at(MONDAY, 9, 0), at(MONDAY, 10, 0), "obs", false, 0L);

		assertThat(appointment.getProcedureTypeId()).isNull();
	}

	@Test
	void keepingTheSameInactiveProcedureOnAnEditIsRefusedLikeAnyOtherValue() {
		Appointment appointment = existingWithProcedure(AppointmentStatus.SCHEDULED, procedureId);
		when(appointments.findOwnById(appointment.getId())).thenReturn(Optional.of(appointment));
		doThrow(new BusinessException(ProblemTypes.BUSINESS_RULE, "O procedimento está inativo.", "PROCEDURE_INACTIVE"))
				.when(procedures).requireActive(procedureId);

		assertRule(() -> service.reschedule(appointment.getId(), at(MONDAY, 9, 0), at(MONDAY, 10, 0), "obs", false, procedureId, 0L),
				ProblemTypes.BUSINESS_RULE, "PROCEDURE_INACTIVE");

		verify(appointments, never()).saveAndFlush(any());
	}

	@Test
	void aWrongVersionOrStatusWinsOverTheProcedureCheck() {
		Appointment stale = existingWithProcedure(AppointmentStatus.SCHEDULED, procedureId);
		Appointment done = existingWithProcedure(AppointmentStatus.COMPLETED, procedureId);
		when(appointments.findOwnById(stale.getId())).thenReturn(Optional.of(stale));
		when(appointments.findOwnById(done.getId())).thenReturn(Optional.of(done));

		assertThatThrownBy(() -> service.reschedule(stale.getId(), at(MONDAY, 9, 0), at(MONDAY, 10, 0), null, false, procedureId, 9L))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.reschedule(done.getId(), at(MONDAY, 9, 0), at(MONDAY, 10, 0), null, false, procedureId, 0L))
				.isInstanceOf(BusinessException.class);

		verifyNoInteractions(procedures);
	}

	@Test
	void theAgendaResolvesAllProceduresInOneCallAndOnlyForTheOnesThatHaveOne() {
		Appointment withProcedure = existingWithProcedure(AppointmentStatus.SCHEDULED, procedureId);
		Appointment sameProcedure = existingWithProcedure(AppointmentStatus.CONFIRMED, procedureId);
		Appointment without = existingWithProcedure(AppointmentStatus.SCHEDULED, null);
		when(appointments.findWindow(any(), any(), any())).thenReturn(List.of(withProcedure, sameProcedure, without));
		when(procedures.briefs(anyCollection())).thenReturn(Map.of(procedureId, new ProcedureBrief(procedureId, "Canal", "#EF4444")));

		List<AppointmentView> views = service.list(MONDAY, MONDAY, List.of());

		assertThat(views).extracting(v -> v.procedure() == null ? null : v.procedure().name()).containsExactly("Canal", "Canal", null);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<java.util.Collection<UUID>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
		verify(procedures).briefs(ids.capture());
		assertThat(ids.getValue()).containsExactly(procedureId);
	}

	@Test
	void anAppointmentWhoseProcedureWasNotResolvedHasANullProcedureInsteadOfFailing() {
		Appointment appointment = existingWithProcedure(AppointmentStatus.SCHEDULED, procedureId);
		when(appointments.findOwnById(appointment.getId())).thenReturn(Optional.of(appointment));
		when(procedures.briefs(anyCollection())).thenReturn(Map.of());

		assertThat(service.get(appointment.getId()).procedure()).isNull();
	}

	@Test
	void theHistoryAlsoCarriesTheProcedureSummary() {
		Appointment appointment = existingWithProcedure(AppointmentStatus.COMPLETED, procedureId);
		when(appointments.findByPatientId(any(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(appointment)));
		when(procedures.briefs(anyCollection())).thenReturn(Map.of(procedureId, new ProcedureBrief(procedureId, "Limpeza", "#10B981")));

		assertThat(service.historyOf(patientId, 0, 20).getContent()).extracting(v -> v.procedure().name()).containsExactly("Limpeza");
	}

	// ---- histórico do paciente ----

	@Test
	void historyIsPagedNewestFirstAndRequiresAnExistingPatient() {
		Appointment newer = existing(AppointmentStatus.COMPLETED, at(MONDAY, 14, 0), 1);
		Appointment older = existing(AppointmentStatus.CANCELED, at(MONDAY, 9, 0), 2);
		when(appointments.findByPatientId(any(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(newer, older)));

		org.springframework.data.domain.Page<AppointmentView> page = service.historyOf(patientId, 1, 20);

		assertThat(page.getContent()).extracting(v -> v.appointment().getId()).containsExactly(newer.getId(), older.getId());
		assertThat(page.getContent()).allSatisfy(v -> assertThat(v.patient().name()).isEqualTo("Maria Souza"));
		ArgumentCaptor<org.springframework.data.domain.Pageable> pageable = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
		verify(appointments).findByPatientId(org.mockito.ArgumentMatchers.eq(patientId), pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
		assertThat(pageable.getValue().getSort().toString()).isEqualTo("startsAt: DESC,id: DESC");
		verify(patients).requireExists(patientId);
	}

	@Test
	void historyOfAnUnknownPatientStopsBeforeTheQuery() {
		doThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado.")).when(patients).requireExists(patientId);

		assertThatThrownBy(() -> service.historyOf(patientId, 0, 20)).isInstanceOf(BusinessException.class);

		verifyNoInteractions(appointments);
	}
}
