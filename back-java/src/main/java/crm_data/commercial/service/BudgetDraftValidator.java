package crm_data.commercial.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import crm_data.catalog.service.ProcedureQuote;
import crm_data.shared.error.ProblemDetails.FieldError;
import crm_data.shared.money.Money;

/**
 * Regra pura (sem Spring) dos limites de valor do rascunho, aplicada <em>antes</em> do banco para devolver o campo
 * inválido em vez de um {@code 23514}. Usa o preço do catálogo do momento (o banco copia o mesmo preço no {@code INSERT}).
 * Todos os erros vêm de uma vez, com o caminho do campo no corpo.
 *
 * <ul>
 * <li>linha = {@code quantidade × preço − desconto do item}, entre 0 e o teto;</li>
 * <li>soma das linhas até o teto;</li>
 * <li>desconto do orçamento até a soma (total nunca negativo).</li>
 * </ul>
 */
public final class BudgetDraftValidator {

	/** Um item como veio do cliente. */
	public record ItemInput(UUID procedureTypeId, int quantity, BigDecimal discountAmount) {

		/** Nunca imprime quantidade nem desconto. */
		@Override
		public String toString() {
			return "ItemInput[procedureTypeId=" + procedureTypeId + "]";
		}
	}

	private BudgetDraftValidator() {
	}

	/**
	 * @param quotes preços dos procedimentos dos itens (todos presentes: a existência já foi checada)
	 * @return erros por campo; vazia se o rascunho é válido
	 */
	public static List<FieldError> validate(List<ItemInput> items, Map<UUID, ProcedureQuote> quotes, BigDecimal budgetDiscount) {
		List<FieldError> errors = new ArrayList<>();
		if (items.isEmpty() || items.size() > CommercialRules.MAX_ITEMS) {
			errors.add(new FieldError("items", "deve ter entre 1 e " + CommercialRules.MAX_ITEMS + " itens"));
		}
		BigDecimal sum = BigDecimal.ZERO;
		for (int i = 0; i < items.size(); i++) {
			ItemInput item = items.get(i);
			if (item.quantity() < CommercialRules.MIN_QUANTITY || item.quantity() > CommercialRules.MAX_QUANTITY) {
				errors.add(new FieldError("items[" + i + "].quantity",
						"deve estar entre " + CommercialRules.MIN_QUANTITY + " e " + CommercialRules.MAX_QUANTITY));
				continue;
			}
			ProcedureQuote quote = quotes.get(item.procedureTypeId());
			if (quote == null) {
				continue; // procedimento inexistente: o service responde 404 antes de chegar aqui
			}
			BigDecimal gross = quote.basePrice().multiply(BigDecimal.valueOf(item.quantity()));
			BigDecimal line = gross.subtract(item.discountAmount());
			if (line.signum() < 0) {
				errors.add(new FieldError("items[" + i + "].discountAmount", "maior que o valor da linha"));
			}
			else if (line.compareTo(Money.CAP) > 0) {
				errors.add(new FieldError("items[" + i + "].quantity", "valor da linha acima do limite de " + Money.format(Money.CAP)));
			}
			else {
				sum = sum.add(line);
			}
		}
		if (sum.compareTo(Money.CAP) > 0) {
			errors.add(new FieldError("items", "soma dos itens acima do limite de " + Money.format(Money.CAP)));
		}
		else if (budgetDiscount.compareTo(sum) > 0) {
			errors.add(new FieldError("discountAmount", "maior que a soma dos itens"));
		}
		return errors;
	}
}
