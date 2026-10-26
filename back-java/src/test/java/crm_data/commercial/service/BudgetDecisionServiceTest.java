package crm_data.commercial.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.repository.BudgetRepository;
import crm_data.commercial.service.BudgetService.BudgetView;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class BudgetDecisionServiceTest {

	private final BudgetRepository budgets = mock(BudgetRepository.class);
	private final BudgetService budgetService = mock(BudgetService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final BudgetDecisionService service = new BudgetDecisionService(budgets, budgetService, events);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final BudgetView reloaded = new BudgetView(null, java.util.List.of(), null, java.util.List.of());

	@BeforeEach
	void setUp() {
		TenantContext.set(clinicId, userId, Role.DENTIST_ADMIN);
		when(budgets.saveAndFlush(any(Budget.class))).thenAnswer(call -> call.getArgument(0));
		when(budgetService.reloaded(any())).thenReturn(reloaded);
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private Budget stored(BudgetStatus status, long version) {
		Budget budget = new Budget(UUID.randomUUID(), UUID.randomUUID(), null, null, null, userId);
		budget.setStatus(status);
		ReflectionTestUtils.setField(budget, "version", version);
		ReflectionTestUtils.setField(budget, "totalAmount", new BigDecimal("800.00"));
		when(budgets.findForUpdateById(budget.getId())).thenReturn(Optional.of(budget));
		return budget;
	}

	@Test
	void approveTurnsASentBudgetIntoApprovedAndPublishesTheEvent() {
		Budget budget = stored(BudgetStatus.SENT, 4);

		BudgetView view = service.approve(budget.getId(), 4L);

		assertThat(view).isSameAs(reloaded);
		assertThat(budget.getStatus()).isEqualTo(BudgetStatus.APPROVED);
		verify(budgets).saveAndFlush(budget);
		verify(events).publishEvent(new CommercialEvents.BudgetDecided(clinicId, budget.getId(), "APPROVED", userId));
	}

	@Test
	void rejectTurnsASentBudgetIntoRejected() {
		Budget budget = stored(BudgetStatus.SENT, 1);

		service.reject(budget.getId(), 1L);

		assertThat(budget.getStatus()).isEqualTo(BudgetStatus.REJECTED);
		verify(events).publishEvent(new CommercialEvents.BudgetDecided(clinicId, budget.getId(), "REJECTED", userId));
	}

	@Test
	void aDraftCannotBeDecidedAndTheMessagePointsToDiscard() {
		Budget approve = stored(BudgetStatus.DRAFT, 0);
		Budget reject = stored(BudgetStatus.DRAFT, 0);

		assertThatThrownBy(() -> service.approve(approve.getId(), 0L)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.reject(reject.getId(), 0L)).isInstanceOf(BusinessException.class)
				.hasMessageContaining("descartar");

		assertThat(approve.getStatus()).isEqualTo(BudgetStatus.DRAFT);
		verify(budgets, never()).saveAndFlush(any());
	}

	@ParameterizedTest
	@EnumSource(value = BudgetStatus.class, names = { "APPROVED", "REJECTED" })
	void terminalBudgetsNeverChange(BudgetStatus terminal) {
		Budget budget = stored(terminal, 2);

		assertThatThrownBy(() -> service.approve(budget.getId(), 2L)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.reject(budget.getId(), 2L)).isInstanceOf(BusinessException.class);

		assertThat(budget.getStatus()).isEqualTo(terminal);
		verify(budgets, never()).saveAndFlush(any());
		verify(events, never()).publishEvent(any());
	}

	@Test
	void aStaleVersionIs409WithTheSharedMessage() {
		Budget budget = stored(BudgetStatus.SENT, 5);

		assertThatThrownBy(() -> service.approve(budget.getId(), 4L)).isInstanceOf(BusinessException.class)
				.hasMessage(ConflictMessages.STALE_VERSION);

		assertThat(budget.getStatus()).isEqualTo(BudgetStatus.SENT);
	}

	@Test
	void anUnknownBudgetIs404() {
		UUID id = UUID.randomUUID();
		when(budgets.findForUpdateById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.approve(id, 0L)).isInstanceOf(BusinessException.class)
				.hasMessageContaining("não encontrado");
	}
}
