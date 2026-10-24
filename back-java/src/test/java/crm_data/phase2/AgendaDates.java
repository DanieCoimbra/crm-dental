package crm_data.phase2;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Datas dos ITs da agenda: sempre no futuro e em semanas sem feriado nacional de data fixa (a regra da recepção bloqueia
 * feriado, e o seed da clínica de teste traz os nacionais de 2026 a 2030). A Sexta-feira Santa é móvel e nunca é usada.
 */
final class AgendaDates {

	static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	private static final Set<String> FIXED_HOLIDAYS = Set.of("01-01", "04-21", "05-01", "09-07", "10-12", "11-02", "11-15", "11-20", "12-25");

	private AgendaDates() {
	}

	private static boolean weekHasHoliday(LocalDate monday) {
		for (int i = 0; i < 7; i++) {
			LocalDate day = monday.plusDays(i);
			if (FIXED_HOLIDAYS.contains("%02d-%02d".formatted(day.getMonthValue(), day.getDayOfMonth()))) {
				return true;
			}
		}
		return false;
	}

	/** Segunda-feira de uma semana sem feriado, a partir de duas semanas à frente. */
	static LocalDate monday() {
		return mondays(1).get(0);
	}

	/** As próximas {@code count} segundas-feiras de semanas sem feriado. */
	static List<LocalDate> mondays(int count) {
		List<LocalDate> result = new ArrayList<>();
		LocalDate monday = LocalDate.now(SAO_PAULO).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);
		while (result.size() < count) {
			if (!weekHasHoliday(monday)) {
				result.add(monday);
			}
			monday = monday.plusWeeks(1);
		}
		return result;
	}

	/**
	 * Uma segunda-feira já passada (a mais recente) cuja semana não tem feriado nacional de data fixa. O dentista também é
	 * bloqueado por feriado sem o encaixe, então "a segunda passada" não pode cair num: o teste dependeria da data de hoje.
	 */
	static LocalDate pastMonday() {
		LocalDate monday = LocalDate.now(SAO_PAULO).with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
		while (weekHasHoliday(monday)) {
			monday = monday.minusWeeks(1);
		}
		return monday;
	}

	/** O instante (ISO-8601, UTC) de {@code date hour:minute} na hora local de São Paulo. */
	static String at(LocalDate date, int hour, int minute) {
		return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(SAO_PAULO).toInstant().toString();
	}
}
