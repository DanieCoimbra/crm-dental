package crm_data.finance.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import crm_data.finance.domain.PaymentMethod;
import crm_data.shared.error.FieldValidationException;
import crm_data.shared.error.ProblemDetails.FieldError;
import crm_data.shared.money.Money;
import crm_data.shared.web.NotBefore2000;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Corpo de {@code POST /installments/{id}/payments}. Não aceita {@code clinicId}, {@code kind}, {@code installmentId},
 * {@code reversesPaymentId}, {@code createdBy} nem {@code status} (campos extras são ignorados). Sem texto livre.
 */
public final class PaymentRequests {

	private PaymentRequests() {
	}

	/** @param amount texto decimal de 0.01 a 999999.99; @param paidOn ausente = hoje na clínica */
	public record PaymentRequest(
			@NotNull(message = "obrigatório") @Pattern(regexp = Money.INPUT_PATTERN, message = FinanceRequests.MONEY_MESSAGE) String amount,
			@NotNull(message = "obrigatório") PaymentMethod method, @NotBefore2000 LocalDate paidOn) {

		/** Valor positivo (o formato já foi validado; zero ainda passa pelo padrão e é recusado aqui). */
		public BigDecimal parsedAmount() {
			try {
				return Money.parsePositive(amount);
			}
			catch (IllegalArgumentException ex) {
				throw new FieldValidationException(List.of(new FieldError("amount", "deve ser de pelo menos 0.01")));
			}
		}
	}
}
