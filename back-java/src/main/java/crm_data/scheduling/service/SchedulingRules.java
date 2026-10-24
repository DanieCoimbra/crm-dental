package crm_data.scheduling.service;

import java.time.Duration;

/** Constantes da agenda (seção 2.3 da spec global). Não há propriedade de configuração: são regras de negócio. */
public final class SchedulingRules {

	/** Tolerância para "agora": a recepção pode agendar até 5 minutos para trás (relógio e digitação). */
	public static final Duration PAST_TOLERANCE = Duration.ofMinutes(5);

	/** Janela máxima da agenda: visão de mês com as bordas. */
	public static final int MAX_AGENDA_DAYS = 42;

	/** Janela máxima da consulta de feriados. */
	public static final int MAX_HOLIDAY_WINDOW_DAYS = 366;

	/**
	 * Recuo da consulta da agenda: o atendimento mais longo dura 12 h, então quem começou antes da janela e entra nela
	 * começou até 12 h antes. Vale enquanto o teto de duração for o do banco ({@code ck_appt_duration}).
	 */
	public static final Duration SEARCH_LOOKBACK = Duration.ofHours(12);

	public static final Duration MIN_DURATION = Duration.ofMinutes(5);
	public static final Duration MAX_DURATION = Duration.ofHours(12);
	public static final Duration DURATION_STEP = Duration.ofMinutes(5);

	public static final int NOTES_MAX = 500;

	/** Itens devolvidos no aviso de agendamentos afetados (o total vai em {@code affectedCount}). */
	public static final int MAX_AFFECTED_ITEMS = 100;

	private SchedulingRules() {
	}
}
