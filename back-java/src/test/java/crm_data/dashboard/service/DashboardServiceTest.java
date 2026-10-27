package crm_data.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import crm_data.clinic.service.ClinicZones;
import crm_data.commercial.service.BudgetLookup;
import crm_data.patient.service.PatientStats;
import crm_data.scheduling.service.AppointmentStats;
import crm_data.scheduling.service.AppointmentStats.ReturnStats;
import crm_data.scheduling.service.AppointmentStats.StartAndStatus;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class DashboardServiceTest {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	private final PatientStats patients = mock(PatientStats.class);
	private final AppointmentStats appointments = mock(AppointmentStats.class);
	private final BudgetLookup budgets = mock(BudgetLookup.class);
	private final ClinicZones zones = mock(ClinicZones.class);
	// terça-feira 27/10/2026, 12:00 em São Paulo
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T15:00:00Z"), ZoneOffset.UTC);
	private final DashboardService service = new DashboardService(patients, appointments, budgets, zones, clock);

	DashboardServiceTest() {
		when(zones.zone()).thenReturn(SAO_PAULO);
		when(appointments.countByStatus(any(), any())).thenReturn(Map.of());
		when(appointments.returnStats(any(), any(), any())).thenReturn(new ReturnStats(0, 0));
		when(appointments.startsAndStatus(any(), any())).thenReturn(List.of());
		when(budgets.approvedTotal(any(), any())).thenReturn(BigDecimal.ZERO);
	}

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	private void as(Role role) {
		TenantContext.set(UUID.randomUUID(), UUID.randomUUID(), role);
	}

	@Test
	void emptyClinicHasZerosNullPercentSevenDaysAndSixStatuses() {
		as(Role.DENTIST_ADMIN);

		DashboardResponse response = service.get(null);

		assertThat(response.totalPatients()).isZero();
		assertThat(response.timezone()).isEqualTo("America/Sao_Paulo");
		assertThat(response.appointmentsToday().total()).isZero();
		assertThat(response.appointmentsToday().byStatus()).containsOnlyKeys("SCHEDULED", "CONFIRMED", "IN_PROGRESS", "COMPLETED", "NO_SHOW",
				"CANCELED").containsValues(0L);
		assertThat(response.returnRate().percent()).isNull();
		assertThat(response.returnRate().month()).isEqualTo("2026-10");
		assertThat(response.weekly().days()).hasSize(7);
		assertThat(response.weekly().weekStart()).isEqualTo(LocalDate.parse("2026-10-26"));
		assertThat(response.weekly().weekEnd()).isEqualTo(LocalDate.parse("2026-11-01"));
		assertThat(response.monthlyRevenue().totalAmount()).isEqualTo("0.00");
	}

	@Test
	void todayTotalIgnoresCanceledAndNoShowButByStatusKeepsAll() {
		as(Role.DENTIST_ADMIN);
		when(appointments.countByStatus(any(), any())).thenReturn(Map.of("COMPLETED", 2L, "CONFIRMED", 1L, "CANCELED", 1L, "NO_SHOW", 3L));

		DashboardResponse.Today today = service.get(null).appointmentsToday();

		assertThat(today.total()).isEqualTo(3);
		assertThat(today.byStatus()).containsEntry("CANCELED", 1L).containsEntry("NO_SHOW", 3L).containsEntry("SCHEDULED", 0L);
	}

	@Test
	void returnRatePercentIsRoundedHalfUpToOneDecimal() {
		as(Role.DENTIST_ADMIN);
		when(appointments.returnStats(any(), any(), any())).thenReturn(new ReturnStats(40, 25));
		assertThat(service.get(null).returnRate().percent()).isEqualTo("62.5");

		when(appointments.returnStats(any(), any(), any())).thenReturn(new ReturnStats(3, 1)); // 33.333...
		assertThat(service.get(null).returnRate().percent()).isEqualTo("33.3");

		when(appointments.returnStats(any(), any(), any())).thenReturn(new ReturnStats(8, 1)); // 12.5 exato
		assertThat(service.get(null).returnRate().percent()).isEqualTo("12.5");

		when(appointments.returnStats(any(), any(), any())).thenReturn(new ReturnStats(16, 1)); // 6.25 -> 6.3 (HALF_UP)
		assertThat(service.get(null).returnRate().percent()).isEqualTo("6.3");

		when(appointments.returnStats(any(), any(), any())).thenReturn(new ReturnStats(5, 5));
		assertThat(service.get(null).returnRate().percent()).isEqualTo("100.0");
	}

	@Test
	void receptionNeverTriggersTheRevenueQueryAndGetsNoRevenueField() {
		as(Role.RECEPTIONIST);

		DashboardResponse response = service.get(null);

		assertThat(response.monthlyRevenue()).isNull();
		verify(budgets, never()).approvedTotal(any(), any());
		verify(budgets, never()).approvedCount(any(), any());
	}

	@Test
	void dentistGetsTheRevenueOfTheCurrentMonth() {
		as(Role.DENTIST_ADMIN);
		when(budgets.approvedTotal(any(), any())).thenReturn(new BigDecimal("18450.5"));
		when(budgets.approvedCount(any(), any())).thenReturn(9L);

		DashboardResponse.MonthlyRevenue revenue = service.get(null).monthlyRevenue();

		assertThat(revenue.month()).isEqualTo("2026-10");
		assertThat(revenue.totalAmount()).isEqualTo("18450.50");
		assertThat(revenue.approvedCount()).isEqualTo(9);
	}

	@Test
	void weekGroupsByTheClinicLocalDayAcrossTheUtcBoundary() {
		as(Role.DENTIST_ADMIN);
		// domingo 01/11 22:00 em São Paulo = segunda 02/11 01:00 UTC; segunda 26/10 00:30 em SP = 03:30 UTC
		when(appointments.startsAndStatus(any(), any())).thenReturn(List.of(
				new StartAndStatus(Instant.parse("2026-11-02T01:00:00Z"), "COMPLETED"),
				new StartAndStatus(Instant.parse("2026-10-26T03:30:00Z"), "CONFIRMED"),
				new StartAndStatus(Instant.parse("2026-10-26T15:00:00Z"), "CANCELED"),
				new StartAndStatus(Instant.parse("2026-10-28T15:00:00Z"), "NO_SHOW")));

		List<DashboardResponse.Day> days = service.get(null).weekly().days();

		assertThat(days.get(0).date()).isEqualTo(LocalDate.parse("2026-10-26"));
		assertThat(days.get(0).total()).as("segunda: confirmada conta, cancelada não").isEqualTo(1);
		assertThat(days.get(0).byStatus()).containsEntry("CONFIRMED", 1L).containsEntry("CANCELED", 1L);
		assertThat(days.get(2).total()).as("quarta: só falta").isZero();
		assertThat(days.get(2).byStatus()).containsEntry("NO_SHOW", 1L);
		assertThat(days.get(6).date()).isEqualTo(LocalDate.parse("2026-11-01"));
		assertThat(days.get(6).total()).as("domingo 22h local").isEqualTo(1);
	}

	@Test
	void weekOfSelectsThatWeek() {
		as(Role.DENTIST_ADMIN);

		DashboardResponse.Weekly weekly = service.get(LocalDate.parse("2026-03-04")).weekly();

		assertThat(weekly.weekStart()).isEqualTo(LocalDate.parse("2026-03-02"));
		assertThat(weekly.weekEnd()).isEqualTo(LocalDate.parse("2026-03-08"));
	}

	@Test
	void weekOfOutOfRangeIs422() {
		as(Role.DENTIST_ADMIN);

		for (LocalDate bad : List.of(LocalDate.parse("1999-12-31"), LocalDate.parse("2027-10-30"))) {
			assertThatThrownBy(() -> service.get(bad)).isInstanceOfSatisfying(BusinessException.class, ex -> {
				assertThat(ex.type()).isEqualTo(ProblemTypes.BUSINESS_RULE);
				assertThat(ex.reason()).isEqualTo("WEEK_OUT_OF_RANGE");
			});
		}
		// limites aceitos: 2000-01-01 e hoje + 366 dias (28/10/2027)
		service.get(LocalDate.parse("2000-01-01"));
		service.get(LocalDate.parse("2027-10-28"));
	}
}
