package crm_data.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.scheduling.service.AppointmentSlotRules.Reason;

class AffectedAppointmentsFinderTest {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
	private static final Instant NOW = Instant.parse("2026-10-24T15:00:00Z");
	/** 2026-11-02 é segunda-feira. */
	private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

	private final AppointmentRepository appointments = mock(AppointmentRepository.class);
	private final PatientLookup patients = mock(PatientLookup.class);
	private final AffectedAppointmentsFinder finder = new AffectedAppointmentsFinder(appointments, patients,
			Clock.fixed(NOW, ZoneOffset.UTC));

	private static final Map<DayOfWeek, DaySchedule> OFFICE = Map.of(DayOfWeek.MONDAY,
			new DaySchedule(true, LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(12, 0), LocalTime.of(13, 0)));

	private Appointment appointment(LocalDate date, int hour, int minute, int minutes, UUID patient) {
		Instant start = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(ZONE).toInstant();
		Appointment appointment = new Appointment(UUID.randomUUID(), patient, start, start.plusSeconds(minutes * 60L), null, UUID.randomUUID());
		ReflectionTestUtils.setField(appointment, "version", 0L);
		return appointment;
	}

	@Test
	void queriesOnlyFromNowMinus12HoursAndReturnsNothingWhenEverythingFits() {
		when(appointments.findOpenFuture(NOW, NOW.minusSeconds(12 * 3600)))
				.thenReturn(List.of(appointment(MONDAY, 9, 0, 60, UUID.randomUUID())));

		AffectedAppointments result = finder.find(OFFICE, ZONE);

		assertThat(result).isSameAs(AffectedAppointments.NONE);
		verify(patients, never()).briefs(anyCollection());
	}

	@Test
	void reportsEachViolationWithItsReasonAndThePatientBriefInOrder() {
		UUID maria = UUID.randomUUID();
		UUID joao = UUID.randomUUID();
		Appointment lunch = appointment(MONDAY, 12, 15, 30, maria);
		Appointment fine = appointment(MONDAY, 9, 0, 60, joao);
		Appointment late = appointment(MONDAY, 17, 30, 60, joao);
		Appointment tuesday = appointment(MONDAY.plusDays(1), 10, 0, 30, maria);
		when(appointments.findOpenFuture(any(), any())).thenReturn(List.of(fine, lunch, late, tuesday));
		when(patients.briefs(anyCollection())).thenReturn(Map.of(maria, new PatientBrief(maria, "Maria Souza", "11987654321"),
				joao, new PatientBrief(joao, "João Lima", "11912345678")));

		AffectedAppointments result = finder.find(OFFICE, ZONE);

		assertThat(result.count()).isEqualTo(3);
		assertThat(result.truncated()).isFalse();
		assertThat(result.items()).extracting(AffectedAppointments.Item::id).containsExactly(lunch.getId(), late.getId(), tuesday.getId());
		assertThat(result.items()).extracting(AffectedAppointments.Item::reason).containsExactly(Reason.LUNCH, Reason.OUTSIDE_HOURS, Reason.CLOSED_DAY);
		assertThat(result.items().get(0).patient().name()).isEqualTo("Maria Souza");
		assertThat(result.items().get(0).status()).isEqualTo(AppointmentStatus.SCHEDULED);
	}

	@Test
	void anUnconfiguredDayCountsAsClosed() {
		when(appointments.findOpenFuture(any(), any())).thenReturn(List.of(appointment(MONDAY, 9, 0, 60, UUID.randomUUID())));

		AffectedAppointments result = finder.find(Map.of(), ZONE);

		assertThat(result.items()).extracting(AffectedAppointments.Item::reason).containsExactly(Reason.CLOSED_DAY);
	}

	@Test
	void holidaysAreIgnoredByTheHoursImpact() {
		Appointment onHoliday = appointment(MONDAY, 9, 0, 60, UUID.randomUUID());
		when(appointments.findOpenFuture(any(), any())).thenReturn(List.of(onHoliday));

		assertThat(finder.find(OFFICE, ZONE)).isSameAs(AffectedAppointments.NONE);
	}

	@Test
	void listIsLimitedTo100ButTheCountIsTheTotalAndBriefsAreOnlyForTheShownOnes() {
		List<Appointment> many = new ArrayList<>();
		for (int i = 0; i < 130; i++) {
			many.add(appointment(MONDAY.plusDays(1), 8 + (i % 8), 0, 30, UUID.randomUUID()));
		}
		when(appointments.findOpenFuture(any(), any())).thenReturn(many);
		when(patients.briefs(anyCollection())).thenReturn(Map.of());

		AffectedAppointments result = finder.find(OFFICE, ZONE);

		assertThat(result.count()).isEqualTo(130);
		assertThat(result.items()).hasSize(100);
		assertThat(result.truncated()).isTrue();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
		verify(patients).briefs(ids.capture());
		assertThat(ids.getValue()).hasSize(100);
	}

	@Test
	void holidayImpactReturnsTheAppointmentsThatTouchTheDate() {
		Appointment onDay = appointment(MONDAY, 9, 0, 60, UUID.randomUUID());
		Appointment other = appointment(MONDAY.plusDays(1), 9, 0, 60, UUID.randomUUID());
		when(appointments.findOpenFuture(any(), any())).thenReturn(List.of(onDay, other));
		when(patients.briefs(anyCollection())).thenReturn(Map.of());

		AffectedAppointments result = finder.findOnHoliday(MONDAY, ZONE);

		assertThat(result.items()).extracting(AffectedAppointments.Item::id).containsExactly(onDay.getId());
		assertThat(result.items().get(0).reason()).isEqualTo(Reason.HOLIDAY);
	}

	@Test
	void itemToStringNeverShowsThePatient() {
		UUID patient = UUID.randomUUID();
		AffectedAppointments.Item item = new AffectedAppointments.Item(UUID.randomUUID(), new PatientBrief(patient, "Maria Souza", "(11) tel-secreto"), NOW,
				NOW.plusSeconds(60), AppointmentStatus.CONFIRMED, Reason.LUNCH);

		assertThat(item.toString()).doesNotContain("Maria", "tel-secreto");
	}
}
