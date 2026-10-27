package crm_data.dashboard.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Janelas de data do dashboard no fuso da clínica. Regra pura (sem Spring nem banco): hoje, mês, os 12 meses anteriores ao
 * mês e a semana de segunda a domingo. Todas são intervalos {@code [ini, fim)} convertidos para {@link Instant}.
 */
public final class DashboardWindows {

	/** Intervalo meio-aberto {@code [ini, fim)}. */
	public record Window(Instant ini, Instant fim) {
	}

	private final ZoneId zone;
	private final LocalDate today;

	public DashboardWindows(Clock clock, ZoneId zone) {
		this.zone = zone;
		this.today = LocalDate.now(clock.withZone(zone));
	}

	public ZoneId zone() {
		return zone;
	}

	/** Hoje no fuso da clínica. */
	public LocalDate localToday() {
		return today;
	}

	/** Mês corrente, como {@code yyyy-MM}. */
	public YearMonth month() {
		return YearMonth.from(today);
	}

	public Window today() {
		return of(today, today.plusDays(1));
	}

	public Window currentMonth() {
		LocalDate first = today.withDayOfMonth(1);
		return of(first, first.plusMonths(1));
	}

	/** Os 12 meses que terminam no início do mês corrente: {@code [mês − 12 meses, início do mês)}. */
	public Window previousTwelveMonths() {
		LocalDate first = today.withDayOfMonth(1);
		return of(first.minusMonths(12), first);
	}

	/** Segunda a domingo da semana que contém {@code ref}. */
	public Window week(LocalDate ref) {
		LocalDate monday = weekStart(ref);
		return of(monday, monday.plusDays(7));
	}

	public static LocalDate weekStart(LocalDate ref) {
		return ref.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	private Window of(LocalDate from, LocalDate to) {
		return new Window(from.atStartOfDay(zone).toInstant(), to.atStartOfDay(zone).toInstant());
	}
}
