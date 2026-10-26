package crm_data.commercial.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import crm_data.catalog.service.ProcedureQuote;
import crm_data.commercial.service.BudgetDraftValidator.ItemInput;
import crm_data.shared.error.ProblemDetails.FieldError;

class BudgetDraftValidatorTest {

	private final UUID restoration = UUID.randomUUID();
	private final UUID root = UUID.randomUUID();
	private final Map<UUID, ProcedureQuote> quotes = Map.of(restoration, new ProcedureQuote(restoration, "Restauração", new BigDecimal("250.00"), true),
			root, new ProcedureQuote(root, "Canal", new BigDecimal("900.00"), true));

	private static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	private List<FieldError> validate(BigDecimal budgetDiscount, ItemInput... items) {
		return BudgetDraftValidator.validate(List.of(items), quotes, budgetDiscount);
	}

	@Test
	void aPlainDraftIsValid() {
		assertThat(validate(d("0.00"), new ItemInput(restoration, 2, d("0.00")), new ItemInput(root, 1, d("100.00")))).isEmpty();
	}

	@Test
	void theDiscountsMayEqualTheLineAndTheSum() {
		assertThat(validate(d("500.00"), new ItemInput(restoration, 2, d("0.00")))).as("desconto = soma").isEmpty();
		assertThat(validate(d("0.00"), new ItemInput(restoration, 1, d("250.00")))).as("desconto da linha = linha").isEmpty();
	}

	@Test
	void anItemDiscountAboveTheLineIsReportedWithTheItemPath() {
		List<FieldError> errors = validate(d("0.00"), new ItemInput(restoration, 1, d("0.00")), new ItemInput(root, 1, d("900.01")));

		assertThat(errors).containsExactly(new FieldError("items[1].discountAmount", "maior que o valor da linha"));
	}

	@Test
	void aBudgetDiscountAboveTheSumOfTheLinesIsReported() {
		List<FieldError> errors = validate(d("750.01"), new ItemInput(restoration, 2, d("0.00")), new ItemInput(root, 1, d("900.00")));

		assertThat(errors).containsExactly(new FieldError("discountAmount", "maior que a soma dos itens"));
	}

	@Test
	void theSumUsesTheLinesAfterTheirOwnDiscounts() {
		// linhas: 500 - 100 = 400 e 900 - 0 = 900 -> soma 1300; desconto 1300 passa, 1300.01 não
		ItemInput a = new ItemInput(restoration, 2, d("100.00"));
		ItemInput b = new ItemInput(root, 1, d("0.00"));

		assertThat(validate(d("1300.00"), a, b)).isEmpty();
		assertThat(validate(d("1300.01"), a, b)).extracting(FieldError::field).containsExactly("discountAmount");
	}

	@Test
	void aLineAboveTheCeilingIsReportedOnTheQuantity() {
		UUID luxury = UUID.randomUUID();
		Map<UUID, ProcedureQuote> luxuryQuotes = Map.of(luxury, new ProcedureQuote(luxury, "Implante", new BigDecimal("999999.99"), true));

		List<FieldError> errors = BudgetDraftValidator.validate(List.of(new ItemInput(luxury, 2, d("0.00"))), luxuryQuotes, d("0.00"));

		assertThat(errors).extracting(FieldError::field).containsExactly("items[0].quantity");
	}

	@Test
	void aDiscountCanBringAHugeLineBackUnderTheCeiling() {
		UUID luxury = UUID.randomUUID();
		Map<UUID, ProcedureQuote> luxuryQuotes = Map.of(luxury, new ProcedureQuote(luxury, "Implante", new BigDecimal("999999.99"), true));

		assertThat(BudgetDraftValidator.validate(List.of(new ItemInput(luxury, 2, d("999999.99"))), luxuryQuotes, d("0.00"))).isEmpty();
	}

	@Test
	void theSumOfTheLinesAboveTheCeilingIsReported() {
		UUID luxury = UUID.randomUUID();
		Map<UUID, ProcedureQuote> luxuryQuotes = Map.of(luxury, new ProcedureQuote(luxury, "Implante", new BigDecimal("600000.00"), true));

		List<FieldError> errors = BudgetDraftValidator.validate(
				List.of(new ItemInput(luxury, 1, d("0.00")), new ItemInput(luxury, 1, d("0.00"))), luxuryQuotes, d("0.00"));

		assertThat(errors).extracting(FieldError::field).containsExactly("items");
	}

	@Test
	void everyErrorComesAtOnceWithItsOwnPath() {
		List<FieldError> errors = validate(d("5000.00"), new ItemInput(restoration, 1, d("999.00")), new ItemInput(root, 1000, d("0.00")),
				new ItemInput(root, 0, d("0.00")));

		assertThat(errors).extracting(FieldError::field)
				.containsExactly("items[0].discountAmount", "items[1].quantity", "items[2].quantity", "discountAmount");
	}

	@Test
	void anEmptyListOrMoreThan50ItemsIsReported() {
		assertThat(BudgetDraftValidator.validate(List.of(), quotes, d("0.00"))).extracting(FieldError::field).containsExactly("items");
		List<ItemInput> many = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			many.add(new ItemInput(restoration, 1, d("0.00")));
		}
		assertThat(BudgetDraftValidator.validate(many, quotes, d("0.00"))).extracting(FieldError::field).contains("items");
		assertThat(BudgetDraftValidator.validate(many.subList(0, 50), quotes, d("0.00"))).isEmpty();
	}

	@Test
	void theInputsNeverPrintValuesOrNotesInToString() {
		ItemInput item = new ItemInput(restoration, 7, d("123.45"));
		crm_data.commercial.service.BudgetService.DraftInput draft = new crm_data.commercial.service.BudgetService.DraftInput(null, null,
				"observação reservada", d("987.65"), List.of(item));

		assertThat(item.toString()).doesNotContain("123.45", "7,");
		assertThat(draft.toString()).doesNotContain("reservada", "987.65", "123.45");
	}

	@Test
	void theMessagesNeverRepeatTheValuesSent() {
		List<FieldError> errors = validate(d("123456.78"), new ItemInput(restoration, 1, d("777.77")));

		assertThat(errors).allSatisfy(error -> assertThat(error.message()).doesNotContain("777.77", "123456.78", "250"));
	}
}
