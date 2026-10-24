package crm_data.scheduling.service;

import java.time.LocalTime;

import crm_data.scheduling.domain.BusinessHour;

/**
 * Expediente de um dia como valor imutável, desacoplado da entidade: permite avaliar uma configuração proposta (aviso
 * de impacto, {@code dryRun}) sem tocar nas entidades gerenciadas. Horários são locais da clínica.
 */
public record DaySchedule(boolean open, LocalTime opensAt, LocalTime closesAt, LocalTime lunchStartsAt,
		LocalTime lunchEndsAt) {

	/** Dia fechado, ou sem linha (não configurado). */
	public static final DaySchedule CLOSED = new DaySchedule(false, null, null, null, null);

	/** O banco aceita {@code 24:00:00}, o {@link LocalTime} não: a leitura limita a 23:59 (a API nunca grava mais). */
	private static final LocalTime LAST_MINUTE = LocalTime.of(23, 59);

	public static DaySchedule of(BusinessHour hour) {
		if (!hour.isOpen()) {
			return CLOSED;
		}
		return new DaySchedule(true, hour.getOpensAt(), clamp(hour.getClosesAt()), hour.getLunchStartsAt(),
				clamp(hour.getLunchEndsAt()));
	}

	private static LocalTime clamp(LocalTime time) {
		return time != null && time.isAfter(LAST_MINUTE) ? LAST_MINUTE : time;
	}
}
