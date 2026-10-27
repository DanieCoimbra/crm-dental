package crm_data.commercial.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.repository.BudgetRepository;

class BudgetLookupServiceTest {

	private final BudgetRepository budgets = mock(BudgetRepository.class);
	private final BudgetLookupService lookup = new BudgetLookupService(budgets);

	@Test
	void briefsCarryOnlyIdNumberAndPatientAndSkipUnknownIds() {
		UUID patientId = UUID.randomUUID();
		Budget budget = new Budget(UUID.randomUUID(), patientId, null, null, "observação", UUID.randomUUID());
		ReflectionTestUtils.setField(budget, "number", 12);
		UUID unknown = UUID.randomUUID();
		when(budgets.findAllByIds(List.of(budget.getId(), unknown))).thenReturn(List.of(budget));

		var briefs = lookup.brief(List.of(budget.getId(), unknown));

		assertThat(briefs).containsOnlyKeys(budget.getId());
		assertThat(briefs.get(budget.getId())).isEqualTo(new BudgetBrief(budget.getId(), 12, patientId));
	}

	@Test
	void requireApprovedReturnsTheBudgetOnlyWhenApprovedAndNeverPrintsTheAmount() {
		Budget approved = budget(BudgetStatus.APPROVED);
		when(budgets.findOwnById(approved.getId())).thenReturn(java.util.Optional.of(approved));

		ApprovedBudget result = lookup.requireApproved(approved.getId());

		assertThat(result.totalAmount()).isEqualByComparingTo("800.00");
		assertThat(result.number()).isEqualTo(12);
		assertThat(result.toString()).doesNotContain("800");
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.EnumSource(value = BudgetStatus.class, names = { "DRAFT", "SENT", "REJECTED" })
	void requireApprovedRefusesAnyOtherStatusWith409(BudgetStatus status) {
		Budget budget = budget(status);
		when(budgets.findOwnById(budget.getId())).thenReturn(java.util.Optional.of(budget));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> lookup.requireApproved(budget.getId()))
				.isInstanceOf(crm_data.shared.error.BusinessException.class);
	}

	@Test
	void lockApprovedLocksTheRowAndTotalOfReadsAnyStatus() {
		Budget approved = budget(BudgetStatus.APPROVED);
		Budget draft = budget(BudgetStatus.DRAFT);
		when(budgets.findForUpdateById(approved.getId())).thenReturn(java.util.Optional.of(approved));
		when(budgets.findOwnById(draft.getId())).thenReturn(java.util.Optional.of(draft));

		assertThat(lookup.lockApproved(approved.getId()).id()).isEqualTo(approved.getId());
		assertThat(lookup.totalOf(draft.getId())).isEqualByComparingTo("800.00");
	}

	@Test
	void anUnknownBudgetIs404() {
		UUID id = UUID.randomUUID();
		when(budgets.findOwnById(id)).thenReturn(java.util.Optional.empty());
		when(budgets.findForUpdateById(id)).thenReturn(java.util.Optional.empty());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> lookup.totalOf(id)).isInstanceOf(crm_data.shared.error.BusinessException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> lookup.requireApproved(id)).isInstanceOf(crm_data.shared.error.BusinessException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> lookup.lockApproved(id)).isInstanceOf(crm_data.shared.error.BusinessException.class);
	}

	private static Budget budget(BudgetStatus status) {
		Budget budget = new Budget(UUID.randomUUID(), UUID.randomUUID(), null, null, null, UUID.randomUUID());
		budget.setStatus(status);
		ReflectionTestUtils.setField(budget, "number", 12);
		ReflectionTestUtils.setField(budget, "totalAmount", new java.math.BigDecimal("800.00"));
		return budget;
	}

	@Test
	void emptyInputNeverHitsTheRepository() {
		assertThat(lookup.brief(List.of())).isEmpty();

		verifyNoInteractions(budgets);
	}
}
