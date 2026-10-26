package crm_data.commercial.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetItem;
import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.service.BudgetService.BudgetView;
import crm_data.commercial.service.BudgetService.DocumentSummary;
import crm_data.shared.money.Money;

/**
 * Orçamento completo na API. Dinheiro em texto decimal; nunca traz {@code clinicId} nem a {@code storageKey} dos PDFs. A
 * recepção recebe o mesmo conteúdo: parcelas e pagamentos não fazem parte desta resposta.
 */
public record BudgetResponse(UUID id, Integer number, PatientRef patient, UUID appointmentId, BudgetStatus status,
		String subtotalAmount, String discountAmount, String totalAmount, LocalDate validUntil, String notes, Instant sentAt,
		Instant decidedAt, UUID createdBy, Instant createdAt, Instant updatedAt, Long version, List<ItemResponse> items,
		List<DocumentRef> documents) {

	/** Paciente resumido: só id e nome. */
	public record PatientRef(UUID id, String name) {
	}

	public record ItemResponse(UUID id, int position, UUID procedureTypeId, String description, int quantity, String unitPrice,
			String discountAmount, String lineTotal) {

		static ItemResponse from(BudgetItem item) {
			return new ItemResponse(item.getId(), item.getPosition(), item.getProcedureTypeId(), item.getDescription(),
					item.getQuantity(), Money.format(item.getUnitPrice()), Money.format(item.getDiscountAmount()),
					Money.format(item.getLineTotal()));
		}
	}

	/** Documento (PDF) ativo: nunca traz a chave do objeto. */
	public record DocumentRef(UUID id, int revision, Instant generatedAt, long sizeBytes) {

		static DocumentRef from(DocumentSummary document) {
			return new DocumentRef(document.id(), document.revision(), document.generatedAt(), document.sizeBytes());
		}
	}

	public static BudgetResponse from(BudgetView view) {
		Budget b = view.budget();
		BigDecimal subtotal = view.items().stream().map(BudgetItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
		PatientRef patient = view.patient() == null ? null : new PatientRef(view.patient().id(), view.patient().name());
		return new BudgetResponse(b.getId(), b.getNumber(), patient, b.getAppointmentId(), b.getStatus(),
				Money.format(subtotal), Money.format(b.getDiscountAmount()), Money.format(b.getTotalAmount()), b.getValidUntil(),
				b.getNotes(), b.getSentAt(), b.getDecidedAt(), b.getCreatedBy(), b.getCreatedAt(), b.getUpdatedAt(),
				b.getVersion(), view.items().stream().map(ItemResponse::from).toList(),
				view.documents().stream().map(DocumentRef::from).toList());
	}
}
