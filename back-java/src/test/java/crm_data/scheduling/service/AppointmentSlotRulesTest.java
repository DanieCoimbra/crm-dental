package crm_data.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import crm_data.scheduling.service.AppointmentSlotRules.Reason;

class AppointmentSlotRulesTest {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final Set<LocalDate> NO_HOLIDAYS = Set.of();
	/** 2026-11-02 é uma segunda-feira. */
	private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

	private static final Map<DayOfWeek, DaySchedule> SCHEDULE = new EnumMap<>(DayOfWeek.class);

	static {
		DaySchedule weekday = new DaySchedule(true, LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(12, 0), LocalTime.of(13, 0));
		for (DayOfWeek day : new DayOfWeek[] { DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY }) {
			SCHEDULE.put(day, weekday);
		}
		SCHEDULE.put(DayOfWeek.SATURDAY, new DaySchedule(true, LocalTime.of(8, 0), LocalTime.of(12, 0), null, null));
		SCHEDULE.put(DayOfWeek.SUNDAY, DaySchedule.CLOSED);
	}

	private static Instant local(LocalDate date, int hour, int minute) {
		return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(SAO_PAULO).toInstant();
	}

	private static Optional<Reason> hours(LocalDate date, int fromH, int fromM, int toH, int toM) {
		return AppointmentSlotRules.checkHours(local(date, fromH, fromM), local(date, toH, toM), SAO_PAULO, SCHEDULE, NO_HOLIDAYS);
	}

	@Test
	void insideTheWorkingHoursIsAllowed() {
		assertThat(hours(MONDAY, 9, 0, 10, 0)).isEmpty();
		assertThat(hours(MONDAY, 13, 0, 18, 0)).as("termina exatamente no fechamento").isEmpty();
		assertThat(hours(MONDAY, 8, 0, 8, 5)).as("começa exatamente na abertura").isEmpty();
	}

	@Test
	void outsideTheWorkingHours() {
		assertThat(hours(MONDAY, 7, 55, 8, 30)).contains(Reason.OUTSIDE_HOURS);
		assertThat(hours(MONDAY, 17, 30, 18, 5)).contains(Reason.OUTSIDE_HOURS);
		assertThat(hours(MONDAY, 19, 0, 20, 0)).contains(Reason.OUTSIDE_HOURS);
	}

	@Test
	void lunchIsHalfOpenSoTouchingItIsAllowed() {
		assertThat(hours(MONDAY, 11, 0, 12, 0)).as("termina quando o almoço começa").isEmpty();
		assertThat(hours(MONDAY, 13, 0, 14, 0)).as("começa quando o almoço termina").isEmpty();
		assertThat(hours(MONDAY, 11, 30, 12, 30)).contains(Reason.LUNCH);
		assertThat(hours(MONDAY, 12, 30, 13, 30)).contains(Reason.LUNCH);
		assertThat(hours(MONDAY, 11, 0, 14, 0)).as("contém o almoço").contains(Reason.LUNCH);
		assertThat(hours(MONDAY, 12, 10, 12, 20)).as("dentro do almoço").contains(Reason.LUNCH);
	}

	@Test
	void dayWithoutLunchHasNoLunchRestriction() {
		LocalDate saturday = MONDAY.plusDays(5);

		assertThat(hours(saturday, 9, 0, 12, 0)).isEmpty();
		assertThat(hours(saturday, 11, 30, 12, 30)).contains(Reason.OUTSIDE_HOURS);
	}

	@Test
	void closedAndUnconfiguredDaysAreClosed() {
		LocalDate sunday = MONDAY.plusDays(6);
		Map<DayOfWeek, DaySchedule> noRows = new EnumMap<>(DayOfWeek.class);

		assertThat(hours(sunday, 9, 0, 10, 0)).contains(Reason.CLOSED_DAY);
		assertThat(AppointmentSlotRules.checkHours(local(MONDAY, 9, 0), local(MONDAY, 10, 0), SAO_PAULO, noRows, NO_HOLIDAYS))
				.contains(Reason.CLOSED_DAY);
	}

	@Test
	void anAppointmentThatCrossesMidnightOrEndsAtMidnightIsOutsideTheHours() {
		Map<DayOfWeek, DaySchedule> late = new EnumMap<>(DayOfWeek.class);
		late.put(DayOfWeek.MONDAY, new DaySchedule(true, LocalTime.of(8, 0), LocalTime.of(23, 59), null, null));
		late.put(DayOfWeek.TUESDAY, new DaySchedule(true, LocalTime.of(0, 0), LocalTime.of(23, 59), null, null));

		assertThat(AppointmentSlotRules.checkHours(local(MONDAY, 23, 30), local(MONDAY.plusDays(1), 0, 30), SAO_PAULO, late, NO_HOLIDAYS))
				.contains(Reason.OUTSIDE_HOURS);
		assertThat(AppointmentSlotRules.checkHours(local(MONDAY, 23, 0), local(MONDAY.plusDays(1), 0, 0), SAO_PAULO, late, NO_HOLIDAYS))
				.contains(Reason.OUTSIDE_HOURS);
	}

	@Test
	void holidayWinsOverEveryOtherHoursReasonAndCoversTheEndDate() {
		LocalDate sunday = MONDAY.plusDays(6);

		assertThat(AppointmentSlotRules.checkHours(local(sunday, 9, 0), local(sunday, 10, 0), SAO_PAULO, SCHEDULE, Set.of(sunday)))
				.as("domingo fechado e feriado: feriado tem prioridade").contains(Reason.HOLIDAY);
		assertThat(AppointmentSlotRules.checkHours(local(MONDAY, 9, 0), local(MONDAY, 10, 0), SAO_PAULO, SCHEDULE, Set.of(MONDAY)))
				.contains(Reason.HOLIDAY);
		assertThat(AppointmentSlotRules.checkHours(local(MONDAY, 23, 0), local(MONDAY.plusDays(1), 1, 0), SAO_PAULO, SCHEDULE,
				Set.of(MONDAY.plusDays(1)))).as("feriado no dia em que termina").contains(Reason.HOLIDAY);
		assertThat(AppointmentSlotRules.checkHours(local(MONDAY, 22, 0), local(MONDAY.plusDays(1), 0, 0), SAO_PAULO, SCHEDULE,
				Set.of(MONDAY.plusDays(1)))).as("termina à meia-noite exata: o dia seguinte não é tocado")
						.isNotEqualTo(Optional.of(Reason.HOLIDAY));
	}

	@Test
	void theVerdictFollowsTheClinicZoneNotUtc() {
		Instant noonLocal = local(MONDAY, 12, 30); // 15:30Z, dentro do almoço em São Paulo

		assertThat(AppointmentSlotRules.checkHours(noonLocal, noonLocal.plusSeconds(1800), SAO_PAULO, SCHEDULE, NO_HOLIDAYS))
				.contains(Reason.LUNCH);
		assertThat(AppointmentSlotRules.checkHours(noonLocal, noonLocal.plusSeconds(1800), ZoneId.of("UTC"), SCHEDULE, NO_HOLIDAYS))
				.as("em UTC são 15:30, dentro do expediente e fora do almoço").isEmpty();
	}

	@Test
	void pastToleranceIsFiveMinutesAndPastHasPriority() {
		Instant now = Instant.parse("2026-11-02T15:00:00Z");

		assertThat(AppointmentSlotRules.isPast(now.minusSeconds(5 * 60), now)).as("exatamente 5 min atrás ainda vale").isFalse();
		assertThat(AppointmentSlotRules.isPast(now.minusSeconds(5 * 60 + 1), now)).isTrue();
		assertThat(AppointmentSlotRules.isPast(now.plusSeconds(60), now)).isFalse();
		Instant start = now.minusSeconds(3600);
		assertThat(AppointmentSlotRules.check(start, start.plusSeconds(1800), now, SAO_PAULO, SCHEDULE, Set.of(MONDAY)))
				.as("PAST antes de HOLIDAY").contains(Reason.PAST);
		assertThat(AppointmentSlotRules.checkHours(start, start.plusSeconds(1800), SAO_PAULO, SCHEDULE, NO_HOLIDAYS))
				.as("checkHours ignora o passado").isEmpty();
	}

	@Test
	void checkFallsThroughToTheHoursWhenNotInThePast() {
		Instant now = Instant.parse("2026-11-01T12:00:00Z");

		assertThat(AppointmentSlotRules.check(local(MONDAY, 12, 30), local(MONDAY, 13, 30), now, SAO_PAULO, SCHEDULE, NO_HOLIDAYS))
				.contains(Reason.LUNCH);
		assertThat(AppointmentSlotRules.check(local(MONDAY, 9, 0), local(MONDAY, 10, 0), now, SAO_PAULO, SCHEDULE, NO_HOLIDAYS))
				.isEmpty();
	}

	@Test
	void touchesDateUsesTheLocalStartAndTheLastInstantBeforeTheExclusiveEnd() {
		assertThat(AppointmentSlotRules.touchesDate(local(MONDAY, 9, 0), local(MONDAY, 10, 0), SAO_PAULO, MONDAY)).isTrue();
		assertThat(AppointmentSlotRules.touchesDate(local(MONDAY, 23, 0), local(MONDAY.plusDays(1), 1, 0), SAO_PAULO, MONDAY.plusDays(1)))
				.isTrue();
		assertThat(AppointmentSlotRules.touchesDate(local(MONDAY, 23, 0), local(MONDAY.plusDays(1), 0, 0), SAO_PAULO, MONDAY.plusDays(1)))
				.as("terminar à meia-noite não toca o dia seguinte").isFalse();
		assertThat(AppointmentSlotRules.touchesDate(local(MONDAY, 9, 0), local(MONDAY, 10, 0), SAO_PAULO, MONDAY.plusDays(1))).isFalse();
	}
}
