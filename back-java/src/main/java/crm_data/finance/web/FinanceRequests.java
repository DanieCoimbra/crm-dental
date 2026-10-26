package crm_data.finance.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import crm_data.finance.service.FinanceRules;
import crm_data.finance.service.PlanRequest;
import crm_data.finance.service.PlanRequest.ManualItem;
import crm_data.finance.service.PlanRequest.Mode;
import crm_data.shared.money.Money;
import crm_data.shared.web.NotBefore2000;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpos de {@code /budgets/{id}/installments} e {@code /installments}. Não aceitam {@code clinicId}, {@code status},
 * {@code number}, {@code netPaid}, {@code balance} nem {@code createdBy} (campos extras são ignorados). Dinheiro é texto
 * decimal. As regras de soma e limites do plano ficam no {@code InstallmentPlanner}.
 */
public final class FinanceRequests {

	static final String MONEY_MESSAGE = "valor monetário inválido (texto decimal de 0 a 999999.99, ex.: \"250.00\")";

	private FinanceRequests() {
	}

	public record ManualItemRequest(@NotNull(message = "obrigatório") @Pattern(regexp = Money.INPUT_PATTERN, message = MONEY_MESSAGE) String amount,
			@NotNull(message = "obrigatório") @NotBefore2000 LocalDate dueDate) {
	}

	/**
	 * @param mode {@code AUTO} ou {@code MANUAL}
	 * @param count (AUTO) de 1 a 60; inclui a entrada
	 * @param installments (MANUAL) de 1 a 60 parcelas
	 */
	public record PlanBody(@NotNull(message = "obrigatório") Mode mode, Integer count, @NotBefore2000 LocalDate firstDueDate,
			@Pattern(regexp = Money.INPUT_PATTERN, message = MONEY_MESSAGE) String downPayment,
			@Size(max = FinanceRules.MAX_INSTALLMENTS, message = "no máximo 60 parcelas") List<@Valid @NotNull(message = "obrigatório") ManualItemRequest> installments) {

		public PlanRequest toRequest() {
			BigDecimal down = downPayment == null ? null : Money.parse(downPayment);
			List<ManualItem> items = installments == null ? null
					: installments.stream().map(i -> new ManualItem(Money.parse(i.amount()), i.dueDate())).toList();
			return new PlanRequest(mode, count, firstDueDate, down, items);
		}
	}

	public record DueDateBody(@NotNull(message = "obrigatório") @NotBefore2000 LocalDate dueDate,
			@NotNull(message = "obrigatório") Long version) {
	}
}
