package crm_data.scheduling.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Regra pura de horário da agenda: passado, feriado, dia fechado, expediente e almoço. Sem Spring e sem estado, para ser
 * testada em unidade e reutilizada pelo aviso de impacto (F02) e pela criação/remarcação (F03). O intervalo é
 * semiaberto {@code [início, fim)}: terminar às 12:00 não invade um almoço que começa às 12:00.
 */
public final class AppointmentSlotRules {

	/** Motivo estável devolvido ao cliente na propriedade {@code reason} do 422. */
	public enum Reason {
		PAST, HOLIDAY, CLOSED_DAY, OUTSIDE_HOURS, LUNCH
	}

	private AppointmentSlotRules() {
	}

	/**
	 * Todas as regras, na ordem de prioridade {@code PAST}, {@code HOLIDAY}, {@code CLOSED_DAY}, {@code OUTSIDE_HOURS},
	 * {@code LUNCH}.
	 */
	public static Optional<Reason> check(Instant start, Instant end, Instant now, ZoneId zone,
			Map<DayOfWeek, DaySchedule> schedule, Set<LocalDate> holidays) {
		if (isPast(start, now)) {
			return Optional.of(Reason.PAST);
		}
		return checkHours(start, end, zone, schedule, holidays);
	}

	/** Início anterior a "agora" menos a tolerância. */
	public static boolean isPast(Instant start, Instant now) {
		return start.isBefore(now.minus(SchedulingRules.PAST_TOLERANCE));
	}

	/** Todas as regras exceto {@code PAST}: é o que o aviso de impacto de uma mudança de expediente avalia. */
	public static Optional<Reason> checkHours(Instant start, Instant end, ZoneId zone,
			Map<DayOfWeek, DaySchedule> schedule, Set<LocalDate> holidays) {
		ZonedDateTime localStart = start.atZone(zone);
		ZonedDateTime localEnd = end.atZone(zone);
		LocalDate startDate = localStart.toLocalDate();
		LocalDate lastDate = localEnd.minusNanos(1).toLocalDate(); // o fim é exclusivo
		if (holidays.contains(startDate) || holidays.contains(lastDate)) {
			return Optional.of(Reason.HOLIDAY);
		}
		DaySchedule day = schedule.getOrDefault(localStart.getDayOfWeek(), DaySchedule.CLOSED);
		if (!day.open()) {
			return Optional.of(Reason.CLOSED_DAY);
		}
		if (!startDate.equals(localEnd.toLocalDate())) {
			return Optional.of(Reason.OUTSIDE_HOURS); // o expediente nunca atravessa a meia-noite
		}
		LocalTime from = localStart.toLocalTime();
		LocalTime to = localEnd.toLocalTime();
		if (from.isBefore(day.opensAt()) || to.isAfter(day.closesAt())) {
			return Optional.of(Reason.OUTSIDE_HOURS);
		}
		if (day.lunchStartsAt() != null && from.isBefore(day.lunchEndsAt()) && to.isAfter(day.lunchStartsAt())) {
			return Optional.of(Reason.LUNCH);
		}
		return Optional.empty();
	}

	/** O atendimento toca o dia {@code date} (início, ou o último instante antes do fim exclusivo)? */
	public static boolean touchesDate(Instant start, Instant end, ZoneId zone, LocalDate date) {
		return start.atZone(zone).toLocalDate().equals(date) || end.atZone(zone).minusNanos(1).toLocalDate().equals(date);
	}
}
