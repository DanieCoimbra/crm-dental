package crm_data.commercial.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import crm_data.commercial.service.BudgetDraftValidator.ItemInput;
import crm_data.commercial.service.BudgetService.DraftInput;
import crm_data.commercial.service.CommercialRules;
import crm_data.shared.money.Money;
import crm_data.shared.web.NotBefore2000;
import crm_data.shared.web.RequestText;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpos de {@code /budgets}. Não aceitam {@code id}, {@code clinicId}, {@code createdBy}, {@code status}, {@code number},
 * {@code totalAmount}, {@code unitPrice}, {@code description}, {@code lineTotal}, {@code sentAt} nem {@code decidedAt}:
 * campos extras do JSON são ignorados (o banco os calcula). Dinheiro é texto decimal ({@code "250.00"}), nunca número
 * JSON. {@code patientId} só existe na criação: o dono do orçamento não muda.
 */
public final class BudgetRequests {

	static final String MONEY_MESSAGE = "valor monetário inválido (texto decimal de 0 a 999999.99, ex.: \"250.00\")";

	private BudgetRequests() {
	}

	/** @param discountAmount desconto da linha em valor; ausente = 0 */
	public record ItemRequest(@NotNull(message = "obrigatório") UUID procedureTypeId,
			@NotNull(message = "obrigatório") @Min(value = CommercialRules.MIN_QUANTITY, message = "deve estar entre 1 e 999") @Max(value = CommercialRules.MAX_QUANTITY, message = "deve estar entre 1 e 999") Integer quantity,
			@Pattern(regexp = Money.INPUT_PATTERN, message = MONEY_MESSAGE) String discountAmount) {

		ItemInput toInput() {
			return new ItemInput(procedureTypeId, quantity, money(discountAmount));
		}
	}

	/**
	 * @param notes observação em texto claro (até 500) que <strong>vai no PDF</strong>; não leva dado clínico
	 * @param discountAmount desconto do orçamento em valor, aplicado sobre a soma dos itens; ausente = 0
	 */
	public record CreateBudgetRequest(@NotNull(message = "obrigatório") UUID patientId, UUID appointmentId,
			@NotBefore2000 LocalDate validUntil,
			@Size(max = CommercialRules.NOTES_MAX, message = "deve ter no máximo 500 caracteres") String notes,
			@Pattern(regexp = Money.INPUT_PATTERN, message = MONEY_MESSAGE) String discountAmount,
			@NotEmpty(message = "informe ao menos um item") @Size(max = CommercialRules.MAX_ITEMS, message = "no máximo 50 itens") List<@Valid @NotNull(message = "obrigatório") ItemRequest> items) {

		public CreateBudgetRequest {
			notes = RequestText.blankToNull(notes);
		}

		public DraftInput toDraft() {
			return new DraftInput(appointmentId, validUntil, notes, money(discountAmount),
					items.stream().map(ItemRequest::toInput).toList());
		}
	}

	/** @param version versão lida pelo cliente (controle de edição concorrente); obrigatória */
	public record UpdateBudgetRequest(UUID appointmentId, @NotBefore2000 LocalDate validUntil,
			@Size(max = CommercialRules.NOTES_MAX, message = "deve ter no máximo 500 caracteres") String notes,
			@Pattern(regexp = Money.INPUT_PATTERN, message = MONEY_MESSAGE) String discountAmount,
			@NotEmpty(message = "informe ao menos um item") @Size(max = CommercialRules.MAX_ITEMS, message = "no máximo 50 itens") List<@Valid @NotNull(message = "obrigatório") ItemRequest> items,
			@NotNull(message = "obrigatório") @Min(value = 0, message = "deve ser maior ou igual a 0") Long version) {

		public UpdateBudgetRequest {
			notes = RequestText.blankToNull(notes);
		}

		public DraftInput toDraft() {
			return new DraftInput(appointmentId, validUntil, notes, money(discountAmount),
					items.stream().map(ItemRequest::toInput).toList());
		}
	}

	/** Corpo das ações sobre o orçamento: só a versão lida pelo cliente. */
	public record ActionRequest(@NotNull(message = "obrigatório") @Min(value = 0, message = "deve ser maior ou igual a 0") Long version) {
	}

	private static BigDecimal money(String text) {
		return text == null ? BigDecimal.ZERO.setScale(2) : Money.parse(text);
	}
}
