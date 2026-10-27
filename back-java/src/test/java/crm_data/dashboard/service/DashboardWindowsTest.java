package crm_data.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import crm_data.dashboard.service.DashboardWindows.Window;

class DashboardWindowsTest {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	private static DashboardWindows at(String utcInstant, ZoneId zone) {
		return new DashboardWindows(Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC), zone);
	}

	private static Instant local(String isoDate, ZoneId zone) {
		return LocalDate.parse(isoDate).atStartOfDay(zone).toInstant();
	}

	@Test
	void todayIsTheClinicLocalDayNotTheUtcDay() {
		// 01:30 UTC de 28/10 ainda é 27/10 (22:30) em São Paulo
		DashboardWindows windows = at("2026-10-28T01:30:00Z", SAO_PAULO);

		assertThat(windows.localToday()).isEqualTo(LocalDate.parse("2026-10-27"));
		assertThat(windows.today()).isEqualTo(new Window(local("2026-10-27", SAO_PAULO), local("2026-10-28", SAO_PAULO)));
		assertThat(windows.today().ini()).isEqualTo(Instant.parse("2026-10-27T03:00:00Z"));
	}

	@Test
	void monthAndTwelvePreviousMonthsAreContiguousAndStartOnTheFirst() {
		DashboardWindows windows = at("2026-10-27T15:00:00Z", SAO_PAULO);

		assertThat(windows.month()).isEqualTo(YearMonth.of(2026, 10));
		assertThat(windows.currentMonth()).isEqualTo(new Window(local("2026-10-01", SAO_PAULO), local("2026-11-01", SAO_PAULO)));
		assertThat(windows.previousTwelveMonths())
				.isEqualTo(new Window(local("2025-10-01", SAO_PAULO), local("2026-10-01", SAO_PAULO)));
		assertThat(windows.previousTwelveMonths().fim()).isEqualTo(windows.currentMonth().ini());
	}

	@Test
	void yearBoundaryRollsMonthAndTwelveMonthWindow() {
		DashboardWindows december = at("2026-12-31T20:00:00Z", SAO_PAULO);
		DashboardWindows january = at("2027-01-01T05:00:00Z", SAO_PAULO);

		assertThat(december.currentMonth().fim()).isEqualTo(local("2027-01-01", SAO_PAULO));
		assertThat(january.month()).isEqualTo(YearMonth.of(2027, 1));
		assertThat(january.previousTwelveMonths().ini()).isEqualTo(local("2026-01-01", SAO_PAULO));
	}

	@Test
	void midnightUtcOnTheFirstIsStillThePreviousMonthInSaoPaulo() {
		DashboardWindows windows = at("2026-11-01T01:00:00Z", SAO_PAULO);

		assertThat(windows.month()).isEqualTo(YearMonth.of(2026, 10));
	}

	@Test
	void weekRunsFromMondayToSundayForEveryDayOfTheWeek() {
		DashboardWindows windows = at("2026-10-27T15:00:00Z", SAO_PAULO);
		Window expected = new Window(local("2026-10-26", SAO_PAULO), local("2026-11-02", SAO_PAULO));

		for (int day = 26; day <= 31; day++) {
			assertThat(windows.week(LocalDate.of(2026, 10, day))).as("outubro %d", day).isEqualTo(expected);
		}
		assertThat(windows.week(LocalDate.of(2026, 11, 1))).as("domingo 1/11").isEqualTo(expected);
		assertThat(windows.week(LocalDate.of(2026, 11, 2)).ini()).as("segunda 2/11 abre a semana seguinte")
				.isEqualTo(local("2026-11-02", SAO_PAULO));
	}

	@Test
	void weekStartIsAlwaysAMonday() {
		assertThat(DashboardWindows.weekStart(LocalDate.of(2026, 10, 26))).isEqualTo(LocalDate.of(2026, 10, 26));
		assertThat(DashboardWindows.weekStart(LocalDate.of(2026, 11, 1))).isEqualTo(LocalDate.of(2026, 10, 26));
		assertThat(DashboardWindows.weekStart(LocalDate.of(2027, 1, 1))).isEqualTo(LocalDate.of(2026, 12, 28));
	}

	@Test
	void anotherZoneChangesTheBoundaries() {
		ZoneId tokyo = ZoneId.of("Asia/Tokyo");
		DashboardWindows windows = at("2026-10-27T20:00:00Z", tokyo); // 28/10 05:00 em Tóquio

		assertThat(windows.localToday()).isEqualTo(LocalDate.parse("2026-10-28"));
		assertThat(windows.today().ini()).isEqualTo(Instant.parse("2026-10-27T15:00:00Z"));
	}
}
