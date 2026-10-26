package crm_data.finance.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Pedido de plano. {@code AUTO}: {@code count}, {@code firstDueDate} e (opcional) {@code downPayment}. {@code MANUAL}:
 * {@code items}. Nunca imprime valores.
 */
public record PlanRequest(Mode mode, Integer count, LocalDate firstDueDate, BigDecimal downPayment, List<ManualItem> items) {

	public enum Mode {
		AUTO, MANUAL
	}

	public record ManualItem(BigDecimal amount, LocalDate dueDate) {

		@Override
		public String toString() {
			return "ManualItem[]";
		}
	}

	@Override
	public String toString() {
		return "PlanRequest[mode=" + mode + "]";
	}
}
