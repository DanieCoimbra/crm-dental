package crm_data.scheduling.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import crm_data.scheduling.domain.AppointmentStatus;

/**
 * Interface pública do módulo {@code scheduling} para o dashboard: leituras agregadas da agenda da clínica do tenant, sem
 * expor repositório nem entidade. Os status trafegam como texto (nome do estado) para o consumidor não depender do domínio.
 */
public interface AppointmentStats {

	/** Os seis status, na ordem do domínio. */
	List<String> STATUSES = Arrays.stream(AppointmentStatus.values()).map(Enum::name).toList();

	/** Status que não entram no "total" de um dia: o horário foi liberado. */
	Set<String> NOT_COUNTED = Set.of(AppointmentStatus.CANCELED.name(), AppointmentStatus.NO_SHOW.name());

	/** Quantos pacientes distintos foram atendidos no mês e quantos deles já tinham outro atendimento concluído antes. */
	record ReturnStats(long attended, long returning) {
	}

	/** Início e status de um agendamento (para agrupar por dia local). */
	record StartAndStatus(Instant startsAt, String status) {
	}

	/** Agendamentos com início em {@code [ini, fim)}, por status (só os status presentes). */
	Map<String, Long> countByStatus(Instant ini, Instant fim);

	/**
	 * Retorno do mês: pacientes distintos com atendimento {@code COMPLETED} em {@code [mesIni, mesFim)} ({@code attended}) e,
	 * entre eles, os que tinham outro {@code COMPLETED} em {@code [anoIni, mesIni)} ({@code returning}).
	 */
	ReturnStats returnStats(Instant mesIni, Instant mesFim, Instant anoIni);

	/** Início e status dos agendamentos com início em {@code [ini, fim)}. */
	List<StartAndStatus> startsAndStatus(Instant ini, Instant fim);
}
