package crm_data.finance.service;

import java.util.UUID;

/** Eventos internos do financeiro. Só identificadores e contagens: nunca valor, vencimento ou nome. */
public final class FinanceEvents {

	private FinanceEvents() {
	}

	/** {@code Installments.PlanCreated}. */
	public record PlanCreated(UUID clinicId, UUID budgetId, int count, UUID userId) {
	}

	/** {@code Installments.PlanRedone}. */
	public record PlanRedone(UUID clinicId, UUID budgetId, int count, UUID userId) {
	}

	/** {@code Installments.DueDateChanged}. */
	public record DueDateChanged(UUID clinicId, UUID installmentId, UUID userId) {
	}

	/** {@code Payment.Registered}. */
	public record PaymentRegistered(UUID clinicId, UUID paymentId, UUID installmentId, UUID userId) {
	}

	/** {@code Payment.Reversed}. */
	public record PaymentReversed(UUID clinicId, UUID paymentId, UUID reversedPaymentId, UUID installmentId, UUID userId) {
	}
}
