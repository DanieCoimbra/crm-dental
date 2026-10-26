package crm_data.finance.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import crm_data.finance.domain.InstallmentStatus;

/**
 * Projeção de leitura da parcela (JPQL {@code select new}). O fluxo de pagamento e os relatórios nunca carregam a
 * entidade {@code Installment}: o trigger do pagamento a altera no banco e o contexto ficaria defasado.
 */
public record InstallmentRow(UUID id, UUID patientId, UUID budgetId, int number, InstallmentStatus status, BigDecimal amount,
		LocalDate dueDate, long version) {

	@Override
	public String toString() {
		return "InstallmentRow[id=" + id + "]";
	}
}
