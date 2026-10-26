package crm_data.finance.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Parcela calculada pelo {@link InstallmentPlanner}, ainda não gravada. */
public record PlannedInstallment(int number, BigDecimal amount, LocalDate dueDate) {

	@Override
	public String toString() {
		return "PlannedInstallment[number=" + number + "]";
	}
}
