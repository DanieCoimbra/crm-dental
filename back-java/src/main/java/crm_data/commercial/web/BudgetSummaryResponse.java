package crm_data.commercial.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.service.BudgetService.BudgetSummary;
import crm_data.commercial.web.BudgetResponse.PatientRef;
import crm_data.shared.money.Money;

/** Orçamento na lista: sem itens, desconto nem observação. Nunca traz {@code clinicId}. */
public record BudgetSummaryResponse(UUID id, Integer number, PatientRef patient, BudgetStatus status, String totalAmount,
		LocalDate validUntil, Instant sentAt, Instant decidedAt, Instant createdAt, Long version) {

	public static BudgetSummaryResponse from(BudgetSummary summary) {
		Budget b = summary.budget();
		PatientRef patient = summary.patient() == null ? null : new PatientRef(summary.patient().id(), summary.patient().name());
		return new BudgetSummaryResponse(b.getId(), b.getNumber(), patient, b.getStatus(), Money.format(b.getTotalAmount()),
				b.getValidUntil(), b.getSentAt(), b.getDecidedAt(), b.getCreatedAt(), b.getVersion());
	}
}
