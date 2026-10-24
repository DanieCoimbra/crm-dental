package crm_data.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.clinic.service.ClinicZones;
import crm_data.scheduling.domain.BusinessHour;
import crm_data.scheduling.repository.BusinessHourRepository;
import crm_data.scheduling.service.BusinessHoursService.DayInput;
import crm_data.scheduling.service.BusinessHoursService.DayView;
import crm_data.scheduling.service.BusinessHoursService.UpdateResult;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class BusinessHoursServiceTest {

	private final BusinessHourRepository hours = mock(BusinessHourRepository.class);
	private final AffectedAppointmentsFinder finder = mock(AffectedAppointmentsFinder.class);
	private final ClinicZones zones = mock(ClinicZones.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final BusinessHoursService service = new BusinessHoursService(hours, finder, zones, events);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	private static final DaySchedule OFFICE = new DaySchedule(true, LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(12, 0),
			LocalTime.of(13, 0));

	@BeforeEach
	void setUp() {
		TenantContext.set(clinicId, userId, Role.DENTIST_ADMIN);
		when(zones.zone()).thenReturn(ZONE);
		when(finder.find(any(), any())).thenReturn(AffectedAppointments.NONE);
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private BusinessHour existing(DayOfWeek day, long version) {
		BusinessHour hour = new BusinessHour(UUID.randomUUID(), day, userId);
		hour.setOpen(true);
		hour.setOpensAt(LocalTime.of(9, 0));
		hour.setClosesAt(LocalTime.of(17, 0));
		ReflectionTestUtils.setField(hour, "version", version);
		return hour;
	}

	@Test
	void weekFillsTheMissingDaysAsNotConfiguredAndClosed() {
		when(hours.findAll()).thenReturn(List.of(existing(DayOfWeek.MONDAY, 2L)));

		List<DayView> week = service.week();

		assertThat(week).hasSize(7);
		assertThat(week.get(0)).isEqualTo(new DayView(DayOfWeek.MONDAY, true,
				new DaySchedule(true, LocalTime.of(9, 0), LocalTime.of(17, 0), null, null), 2L));
		assertThat(week.subList(1, 7)).allSatisfy(view -> {
			assertThat(view.configured()).isFalse();
			assertThat(view.schedule().open()).isFalse();
			assertThat(view.version()).isNull();
		});
		assertThat(week.stream().map(DayView::day)).containsExactly(DayOfWeek.values());
	}

	@Test
	void weekScheduleOnlyHasTheConfiguredDays() {
		when(hours.findAll()).thenReturn(List.of(existing(DayOfWeek.TUESDAY, 0L)));

		assertThat(service.weekSchedule()).containsOnlyKeys(DayOfWeek.TUESDAY);
	}

	@Test
	void firstSaveCreatesTheDayWithTheTokenUserAndPublishesTheEvent() {
		when(hours.findAll()).thenReturn(List.of());

		UpdateResult result = service.update(List.of(new DayInput(DayOfWeek.MONDAY, OFFICE, null)), false);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<BusinessHour>> saved = ArgumentCaptor.forClass(List.class);
		verify(hours).saveAllAndFlush(saved.capture());
		BusinessHour created = saved.getValue().get(0);
		assertThat(created.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
		assertThat(created.getCreatedBy()).isEqualTo(userId);
		assertThat(created.isOpen()).isTrue();
		assertThat(created.getLunchStartsAt()).isEqualTo(LocalTime.of(12, 0));
		assertThat(result.saved()).isTrue();
		verify(events).publishEvent(new SchedulingEvents.BusinessHoursChanged(clinicId, userId, 0));
	}

	@Test
	void updateAppliesTheNewScheduleToAnExistingDay() {
		BusinessHour monday = existing(DayOfWeek.MONDAY, 3L);
		when(hours.findAll()).thenReturn(List.of(monday));

		service.update(List.of(new DayInput(DayOfWeek.MONDAY, OFFICE, 3L)), false);

		assertThat(monday.getOpensAt()).isEqualTo(LocalTime.of(8, 0));
		assertThat(monday.getClosesAt()).isEqualTo(LocalTime.of(18, 0));
		assertThat(monday.getLunchEndsAt()).isEqualTo(LocalTime.of(13, 0));
	}

	@Test
	void closingADayClearsEveryTime() {
		BusinessHour monday = existing(DayOfWeek.MONDAY, 1L);
		monday.setLunchStartsAt(LocalTime.of(12, 0));
		monday.setLunchEndsAt(LocalTime.of(13, 0));
		when(hours.findAll()).thenReturn(List.of(monday));

		service.update(List.of(new DayInput(DayOfWeek.MONDAY, DaySchedule.CLOSED, 1L)), false);

		assertThat(monday.isOpen()).isFalse();
		assertThat(monday.getOpensAt()).isNull();
		assertThat(monday.getClosesAt()).isNull();
		assertThat(monday.getLunchStartsAt()).isNull();
		assertThat(monday.getLunchEndsAt()).isNull();
	}

	@Test
	void missingOrStaleOrUnexpectedVersionIsConflictAndNothingIsSaved() {
		when(hours.findAll()).thenReturn(List.of(existing(DayOfWeek.MONDAY, 3L)));

		for (DayInput input : List.of(new DayInput(DayOfWeek.MONDAY, OFFICE, null), new DayInput(DayOfWeek.MONDAY, OFFICE, 2L),
				new DayInput(DayOfWeek.TUESDAY, OFFICE, 0L))) {
			assertThatThrownBy(() -> service.update(List.of(input), false)).isInstanceOfSatisfying(BusinessException.class,
					ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));
		}
		verify(hours, never()).saveAllAndFlush(anyList());
		verifyNoInteractions(events);
	}

	@Test
	void aStaleVersionInOneDayStopsTheWholeBatch() {
		when(hours.findAll()).thenReturn(List.of(existing(DayOfWeek.MONDAY, 3L)));

		assertThatThrownBy(() -> service.update(
				List.of(new DayInput(DayOfWeek.TUESDAY, OFFICE, null), new DayInput(DayOfWeek.MONDAY, OFFICE, 9L)), false))
						.isInstanceOf(BusinessException.class);
		verify(hours, never()).saveAllAndFlush(anyList());
	}

	@Test
	void dryRunValidatesButNeverWritesNorPublishesAndKeepsEntitiesUntouched() {
		BusinessHour monday = existing(DayOfWeek.MONDAY, 3L);
		when(hours.findAll()).thenReturn(List.of(monday));

		UpdateResult result = service.update(List.of(new DayInput(DayOfWeek.MONDAY, OFFICE, 3L),
				new DayInput(DayOfWeek.TUESDAY, OFFICE, null)), true);

		assertThat(result.saved()).isFalse();
		assertThat(monday.getOpensAt()).as("a entidade gerenciada não é alterada").isEqualTo(LocalTime.of(9, 0));
		verify(hours, never()).saveAllAndFlush(anyList());
		verifyNoInteractions(events);
		assertThat(result.days().get(0).schedule()).isEqualTo(OFFICE);
		assertThat(result.days().get(1).configured()).as("terça passaria a configurada").isTrue();
		assertThat(result.days().get(1).version()).isNull();
	}

	@Test
	void dryRunWithAStaleVersionIsStillConflict() {
		when(hours.findAll()).thenReturn(List.of(existing(DayOfWeek.MONDAY, 3L)));

		assertThatThrownBy(() -> service.update(List.of(new DayInput(DayOfWeek.MONDAY, OFFICE, 1L)), true))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void theImpactIsEvaluatedAgainstTheResultingWeekAndSavedEventCarriesTheCount() {
		when(hours.findAll()).thenReturn(List.of(existing(DayOfWeek.WEDNESDAY, 0L)));
		AffectedAppointments affected = new AffectedAppointments(List.of(), 4, false);
		when(finder.find(any(), any())).thenReturn(affected);

		UpdateResult result = service.update(List.of(new DayInput(DayOfWeek.MONDAY, OFFICE, null)), false);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<DayOfWeek, DaySchedule>> resulting = ArgumentCaptor.forClass(Map.class);
		verify(finder).find(resulting.capture(), any());
		assertThat(resulting.getValue()).containsOnlyKeys(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
		assertThat(resulting.getValue().get(DayOfWeek.MONDAY)).isEqualTo(OFFICE);
		assertThat(resulting.getValue().get(DayOfWeek.WEDNESDAY).opensAt()).isEqualTo(LocalTime.of(9, 0));
		assertThat(result.affected()).isSameAs(affected);
		verify(events).publishEvent(new SchedulingEvents.BusinessHoursChanged(clinicId, userId, 4));
	}
}
