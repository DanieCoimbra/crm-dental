package crm_data.dashboard.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Dashboard na API. Só agregados (nenhum dado pessoal). {@code monthlyRevenue} é omitido (não vem {@code null}) para a
 * recepção. {@code byStatus} sempre traz os seis status e {@code days} sempre traz sete dias, segunda a domingo.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardResponse(Instant generatedAt, String timezone, long totalPatients, Today appointmentsToday,
		ReturnRate returnRate, MonthlyRevenue monthlyRevenue, Weekly weekly) {

	/** Consultas de hoje; {@code total} exclui cancelados e faltas. */
	public record Today(long total, Map<String, Long> byStatus) {
	}

	/** Taxa de retorno do mês; {@code percent} com 1 casa (texto) ou {@code null} sem atendidos. */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record ReturnRate(String month, long attended, long returning, String percent) {
	}

	/** Faturamento por competência (orçamentos aprovados no mês); {@code totalAmount} em texto com 2 casas. */
	public record MonthlyRevenue(String month, String totalAmount, long approvedCount) {
	}

	public record Weekly(LocalDate weekStart, LocalDate weekEnd, List<Day> days) {
	}

	public record Day(LocalDate date, long total, Map<String, Long> byStatus) {
	}
}
