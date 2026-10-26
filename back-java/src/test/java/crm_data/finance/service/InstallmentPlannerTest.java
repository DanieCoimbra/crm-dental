package crm_data.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import crm_data.finance.service.PlanRequest.ManualItem;
import crm_data.finance.service.PlanRequest.Mode;
import crm_data.shared.error.FieldValidationException;

class InstallmentPlannerTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	private static PlanRequest auto(int count, String firstDue, String down) {
		return new PlanRequest(Mode.AUTO, count, LocalDate.parse(firstDue), down == null ? null : money(down), null);
	}

	private static BigDecimal sum(List<PlannedInstallment> plan) {
		return plan.stream().map(PlannedInstallment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	@Test
	void autoWithoutDownSplitsDownAndTheLastOneAbsorbsTheCents() {
		List<PlannedInstallment> plan = InstallmentPlanner.plan(money("100.00"), auto(3, "2026-11-10", null), TODAY);

		assertThat(plan).extracting(PlannedInstallment::amount).containsExactly(money("33.33"), money("33.33"), money("33.34"));
		assertThat(plan).extracting(PlannedInstallment::number).containsExactly(1, 2, 3);
		assertThat(plan).extracting(PlannedInstallment::dueDate).containsExactly(LocalDate.parse("2026-11-10"),
				LocalDate.parse("2026-12-10"), LocalDate.parse("2027-01-10"));
	}

	@Test
	void monthEndDueDatesClampInsteadOfOverflowing() {
		List<PlannedInstallment> plan = InstallmentPlanner.plan(money("300.00"), auto(3, "2027-01-31", null), TODAY);

		assertThat(plan).extracting(PlannedInstallment::dueDate).containsExactly(LocalDate.parse("2027-01-31"),
				LocalDate.parse("2027-02-28"), LocalDate.parse("2027-03-31"));
	}

	@Test
	void theDownPaymentIsInstallmentOneDueTodayAndCountIncludesIt() {
		List<PlannedInstallment> plan = InstallmentPlanner.plan(money("800.00"), auto(4, "2026-12-05", "200.00"), TODAY);

		assertThat(plan).hasSize(4);
		assertThat(plan.get(0)).isEqualTo(new PlannedInstallment(1, money("200.00"), TODAY));
		assertThat(plan.subList(1, 4)).extracting(PlannedInstallment::amount).containsExactly(money("200.00"), money("200.00"), money("200.00"));
		assertThat(plan.get(1).dueDate()).isEqualTo(LocalDate.parse("2026-12-05"));
		assertThat(plan.get(3).dueDate()).isEqualTo(LocalDate.parse("2027-02-05"));
	}

	@Test
	void aSingleInstallmentCarriesTheWholeTotal() {
		List<PlannedInstallment> plan = InstallmentPlanner.plan(money("0.01"), auto(1, "2026-11-01", null), TODAY);

		assertThat(plan).containsExactly(new PlannedInstallment(1, money("0.01"), LocalDate.parse("2026-11-01")));
	}

	@Test
	void aZeroDownPaymentIsTreatedAsNoDownPayment() {
		List<PlannedInstallment> plan = InstallmentPlanner.plan(money("100.00"), auto(2, "2026-11-10", "0.00"), TODAY);

		assertThat(plan).extracting(PlannedInstallment::amount).containsExactly(money("50.00"), money("50.00"));
		assertThat(plan.get(0).dueDate()).isEqualTo(LocalDate.parse("2026-11-10"));
	}

	@Test
	void invalidAutoRequestsAreRejectedWithTheOffendingField() {
		assertField(auto(0, "2026-11-10", null), "count");
		assertField(auto(61, "2026-11-10", null), "count");
		assertField(new PlanRequest(Mode.AUTO, null, LocalDate.parse("2026-11-10"), null, null), "count");
		assertField(new PlanRequest(Mode.AUTO, 3, null, null, null), "firstDueDate");
		assertField(auto(3, "1999-12-31", null), "firstDueDate");
		assertField(auto(3, "2026-11-10", "100.00"), "downPayment");   // entrada == total
		assertField(auto(3, "2026-11-10", "150.00"), "downPayment");   // entrada > total
		assertField(auto(1, "2026-11-10", "10.00"), "count");          // entrada exige ao menos 2 parcelas
		assertField(new PlanRequest(null, 3, LocalDate.parse("2026-11-10"), null, null), "mode");
	}

	@Test
	void aPartBelowOneCentIsRejected() {
		assertThatThrownBy(() -> InstallmentPlanner.plan(money("0.02"), auto(3, "2026-11-10", null), TODAY))
				.isInstanceOf(FieldValidationException.class);
		// entrada de 0,01 sobre 0,02 em 2 parcelas é válida: 0,01 + 0,01
		assertThat(InstallmentPlanner.plan(money("0.02"), auto(2, "2026-11-10", "0.01"), TODAY)).hasSize(2);
	}

	private static void assertField(PlanRequest request, String expectedField) {
		assertThatThrownBy(() -> InstallmentPlanner.plan(money("100.00"), request, TODAY)).isInstanceOfSatisfying(FieldValidationException.class,
				ex -> assertThat(ex.errors()).extracting(error -> error.field()).contains(expectedField));
	}

	@Test
	void manualSortsByDueDateKeepingTheReceivedOrderOnTies() {
		PlanRequest request = new PlanRequest(Mode.MANUAL, null, null, null, List.of(
				new ManualItem(money("300.00"), LocalDate.parse("2026-12-01")),
				new ManualItem(money("100.00"), LocalDate.parse("2026-11-01")),
				new ManualItem(money("400.00"), LocalDate.parse("2026-12-01"))));

		List<PlannedInstallment> plan = InstallmentPlanner.plan(money("800.00"), request, TODAY);

		assertThat(plan).extracting(PlannedInstallment::amount).containsExactly(money("100.00"), money("300.00"), money("400.00"));
		assertThat(plan).extracting(PlannedInstallment::number).containsExactly(1, 2, 3);
	}

	@Test
	void manualWithTheWrongSumReportsTheDifference() {
		PlanRequest short_ = new PlanRequest(Mode.MANUAL, null, null, null,
				List.of(new ManualItem(money("700.00"), LocalDate.parse("2026-12-01"))));
		PlanRequest over = new PlanRequest(Mode.MANUAL, null, null, null,
				List.of(new ManualItem(money("850.50"), LocalDate.parse("2026-12-01"))));

		assertThatThrownBy(() -> InstallmentPlanner.plan(money("800.00"), short_, TODAY)).isInstanceOfSatisfying(
				FieldValidationException.class, ex -> assertThat(ex.errors().get(0).message()).contains("100.00").contains("faltam"));
		assertThatThrownBy(() -> InstallmentPlanner.plan(money("800.00"), over, TODAY)).isInstanceOfSatisfying(
				FieldValidationException.class, ex -> assertThat(ex.errors().get(0).message()).contains("50.50").contains("excedem"));
	}

	@Test
	void manualRejectsBadLimitsAndPointsToTheIndexedField() {
		PlanRequest empty = new PlanRequest(Mode.MANUAL, null, null, null, List.of());
		PlanRequest tooMany = new PlanRequest(Mode.MANUAL, null, null, null, java.util.Collections.nCopies(61,
				new ManualItem(money("1.00"), LocalDate.parse("2026-12-01"))));
		PlanRequest tiny = new PlanRequest(Mode.MANUAL, null, null, null, List.of(
				new ManualItem(money("0.00"), LocalDate.parse("2026-12-01")), new ManualItem(money("800.00"), LocalDate.parse("1999-01-01"))));

		assertThatThrownBy(() -> InstallmentPlanner.plan(money("800.00"), empty, TODAY)).isInstanceOf(FieldValidationException.class);
		assertThatThrownBy(() -> InstallmentPlanner.plan(money("800.00"), tooMany, TODAY)).isInstanceOf(FieldValidationException.class);
		assertThatThrownBy(() -> InstallmentPlanner.plan(money("800.00"), tiny, TODAY)).isInstanceOfSatisfying(FieldValidationException.class,
				ex -> assertThat(ex.errors()).extracting(error -> error.field())
						.containsExactly("installments[0].amount", "installments[1].dueDate"));
	}

	/** Propriedade: para totais, contagens e entradas aleatórios, Σ parcelas == total e nenhuma parcela é menor que R$ 0,01. */
	@Test
	void theSumAlwaysEqualsTheTotalForRandomPlans() {
		Random random = new Random(20261026L);
		int checked = 0;
		for (int round = 0; round < 1000; round++) {
			BigDecimal total = BigDecimal.valueOf(1 + random.nextInt(99_999_999), 2); // 0,01 .. 999999,99
			int count = 1 + random.nextInt(60);
			boolean withDown = count >= 2 && total.compareTo(money("0.02")) > 0 && random.nextBoolean();
			BigDecimal down = withDown ? BigDecimal.valueOf(1 + random.nextInt(total.unscaledValue().intValue() - 1), 2) : null;
			PlanRequest request = new PlanRequest(Mode.AUTO, count, LocalDate.of(2026, 1, 31).plusDays(random.nextInt(900)), down, null);

			List<PlannedInstallment> plan;
			try {
				plan = InstallmentPlanner.plan(total, request, TODAY);
			}
			catch (FieldValidationException ex) {
				continue; // total pequeno demais para tantas partes: recusado, nunca arredondado
			}
			checked++;
			assertThat(sum(plan)).as("total %s, count %d, down %s", total, count, down).isEqualByComparingTo(total);
			assertThat(plan).hasSize(count);
			assertThat(plan).allSatisfy(p -> assertThat(p.amount()).isGreaterThanOrEqualTo(money("0.01")));
			List<Integer> numbers = new ArrayList<>();
			plan.forEach(p -> numbers.add(p.number()));
			assertThat(numbers).isEqualTo(java.util.stream.IntStream.rangeClosed(1, count).boxed().toList());
		}
		assertThat(checked).isGreaterThanOrEqualTo(200);
	}

	@Test
	void theManualSumPropertyHoldsForRandomSplits() {
		Random random = new Random(7L);
		for (int round = 0; round < 300; round++) {
			int cents = 60 + random.nextInt(1_000_000);
			int parts = 1 + random.nextInt(60);
			// cortes distintos em 1..cents-1 dão partes de pelo menos 1 centavo cuja soma é o total
			java.util.TreeSet<Integer> cuts = new java.util.TreeSet<>();
			while (cuts.size() < parts - 1) {
				cuts.add(1 + random.nextInt(cents - 1));
			}
			List<ManualItem> items = new ArrayList<>();
			int previous = 0;
			for (int cut : cuts) {
				items.add(new ManualItem(BigDecimal.valueOf(cut - previous, 2), LocalDate.of(2026, 11, 1).plusDays(random.nextInt(400))));
				previous = cut;
			}
			items.add(new ManualItem(BigDecimal.valueOf(cents - previous, 2), LocalDate.of(2026, 11, 1).plusDays(random.nextInt(400))));
			BigDecimal total = BigDecimal.valueOf(cents, 2);

			List<PlannedInstallment> plan = InstallmentPlanner.plan(total, new PlanRequest(Mode.MANUAL, null, null, null, items), TODAY);

			assertThat(sum(plan)).isEqualByComparingTo(total);
			assertThat(plan).extracting(PlannedInstallment::dueDate).isSorted();
		}
	}

	@Test
	void toStringNeverPrintsMoney() {
		assertThat(new PlannedInstallment(1, money("123.45"), TODAY).toString()).doesNotContain("123");
		assertThat(auto(3, "2026-11-10", "50.00").toString()).doesNotContain("50");
		assertThat(new ManualItem(money("999.99"), TODAY).toString()).doesNotContain("999");
	}
}
