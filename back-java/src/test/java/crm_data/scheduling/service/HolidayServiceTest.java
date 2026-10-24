package crm_data.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.clinic.service.ClinicZones;
import crm_data.scheduling.domain.ClinicHoliday;
import crm_data.scheduling.domain.NationalHoliday;
import crm_data.scheduling.repository.ClinicHolidayRepository;
import crm_data.scheduling.repository.NationalHolidayRepository;
import crm_data.scheduling.service.HolidayService.HolidayChange;
import crm_data.scheduling.service.HolidayService.HolidayView;
import crm_data.scheduling.service.HolidayService.Source;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class HolidayServiceTest {

	private final ClinicHolidayRepository clinicHolidays = mock(ClinicHolidayRepository.class);
	private final NationalHolidayRepository nationalHolidays = mock(NationalHolidayRepository.class);
	private final AffectedAppointmentsFinder finder = mock(AffectedAppointmentsFinder.class);
	private final ClinicZones zones = mock(ClinicZones.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final HolidayService service = new HolidayService(clinicHolidays, nationalHolidays, finder, zones, events);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private static final LocalDate DAY = LocalDate.of(2026, 12, 24);

	@BeforeEach
	void setUp() {
		TenantContext.set(clinicId, userId, Role.DENTIST_ADMIN);
		when(zones.zone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
		when(finder.findOnHoliday(any(), any())).thenReturn(AffectedAppointments.NONE);
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private static NationalHoliday national(LocalDate date, String name) {
		NationalHoliday holiday = mock(NationalHoliday.class);
		when(holiday.getDate()).thenReturn(date);
		when(holiday.getName()).thenReturn(name);
		return holiday;
	}

	private ClinicHoliday clinic(LocalDate date, String name, long version) {
		ClinicHoliday holiday = new ClinicHoliday(UUID.randomUUID(), date, name, userId);
		ReflectionTestUtils.setField(holiday, "version", version);
		return holiday;
	}

	@Test
	void listMergesNationalAndClinicHolidaysSortedByDateWithNationalFirstOnTheSameDate() {
		LocalDate from = LocalDate.of(2026, 12, 1);
		LocalDate to = LocalDate.of(2026, 12, 31);
		ClinicHoliday recess = clinic(LocalDate.of(2026, 12, 25), "Recesso", 2L);
		NationalHoliday christmas = national(LocalDate.of(2026, 12, 25), "Natal");
		ClinicHoliday eve = clinic(DAY, "Véspera", 0L);
		when(nationalHolidays.findByDateBetweenOrderByDate(from, to)).thenReturn(List.of(christmas));
		when(clinicHolidays.findByDateBetweenOrderByDate(from, to)).thenReturn(List.of(eve, recess));

		List<HolidayView> views = service.list(from, to);

		assertThat(views).extracting(HolidayView::name).containsExactly("Véspera", "Natal", "Recesso");
		assertThat(views).extracting(HolidayView::source).containsExactly(Source.CLINIC, Source.NATIONAL, Source.CLINIC);
		assertThat(views.get(1).id()).isNull();
		assertThat(views.get(1).version()).isNull();
		assertThat(views.get(2).id()).isEqualTo(recess.getId());
		assertThat(views.get(2).version()).isEqualTo(2L);
	}

	@Test
	void listRejectsAnInvertedWindowAndMoreThan366Days() {
		LocalDate from = LocalDate.of(2026, 1, 1);

		assertThatThrownBy(() -> service.list(from, from.minusDays(1))).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.VALIDATION));
		assertThatThrownBy(() -> service.list(from, from.plusDays(366))).isInstanceOf(BusinessException.class);
		assertThat(service.list(from, from.plusDays(365))).as("366 dias inclusivos é o limite").isEmpty();
	}

	@Test
	void holidayDatesIsTheUnionOfBothSources() {
		LocalDate from = LocalDate.of(2026, 12, 1);
		LocalDate to = LocalDate.of(2026, 12, 31);
		NationalHoliday christmas = national(LocalDate.of(2026, 12, 25), "Natal");
		ClinicHoliday eve = clinic(DAY, "Véspera", 0L);
		when(nationalHolidays.findByDateBetweenOrderByDate(from, to)).thenReturn(List.of(christmas));
		when(clinicHolidays.findByDateBetweenOrderByDate(from, to)).thenReturn(List.of(eve));

		assertThat(service.holidayDates(from, to)).isEqualTo(Set.of(LocalDate.of(2026, 12, 25), DAY));
	}

	@Test
	void createSavesWithTheTokenUserAndReturnsTheImpactAndPublishesTheEvent() {
		when(clinicHolidays.saveAndFlush(any(ClinicHoliday.class))).thenAnswer(call -> call.getArgument(0));
		AffectedAppointments affected = new AffectedAppointments(List.of(), 3, false);
		when(finder.findOnHoliday(DAY, ZoneId.of("America/Sao_Paulo"))).thenReturn(affected);

		HolidayChange change = service.create(DAY, "Recesso");

		assertThat(change.holiday().name()).isEqualTo("Recesso");
		assertThat(change.holiday().source()).isEqualTo(Source.CLINIC);
		assertThat(change.affected()).isSameAs(affected);
		verify(events).publishEvent(new SchedulingEvents.ClinicHolidayChanged(clinicId, userId, change.holiday().id(), "criado", 3));
	}

	@Test
	void updateChangesDateAndNameWhenTheVersionMatches() {
		ClinicHoliday holiday = clinic(DAY, "Recesso", 4L);
		when(clinicHolidays.findById(holiday.getId())).thenReturn(Optional.of(holiday));
		when(clinicHolidays.saveAndFlush(holiday)).thenReturn(holiday);

		HolidayChange change = service.update(holiday.getId(), DAY.plusDays(1), "Novo nome", 4L);

		assertThat(holiday.getDate()).isEqualTo(DAY.plusDays(1));
		assertThat(holiday.getName()).isEqualTo("Novo nome");
		assertThat(change.holiday().date()).isEqualTo(DAY.plusDays(1));
		verify(finder).findOnHoliday(DAY.plusDays(1), ZoneId.of("America/Sao_Paulo"));
	}

	@Test
	void updateWithStaleVersionIsConflictAndUnknownIdIsNotFound() {
		ClinicHoliday holiday = clinic(DAY, "Recesso", 4L);
		when(clinicHolidays.findById(holiday.getId())).thenReturn(Optional.of(holiday));
		UUID unknown = UUID.randomUUID();
		when(clinicHolidays.findById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.update(holiday.getId(), DAY, "x1", 3L)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));
		assertThatThrownBy(() -> service.update(unknown, DAY, "x1", 0L)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
		assertThat(holiday.getName()).isEqualTo("Recesso");
		verify(clinicHolidays, never()).saveAndFlush(any());
	}

	@Test
	void deleteRemovesPhysicallyWithoutImpactAndUnknownIdIsNotFound() {
		ClinicHoliday holiday = clinic(DAY, "Recesso", 0L);
		when(clinicHolidays.findById(holiday.getId())).thenReturn(Optional.of(holiday));
		UUID unknown = UUID.randomUUID();
		when(clinicHolidays.findById(unknown)).thenReturn(Optional.empty());

		service.delete(holiday.getId());

		verify(clinicHolidays).delete(holiday);
		verify(clinicHolidays).flush();
		verifyNoInteractions(finder);
		verify(events).publishEvent(new SchedulingEvents.ClinicHolidayChanged(clinicId, userId, holiday.getId(), "removido", 0));
		assertThatThrownBy(() -> service.delete(unknown)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}
}
