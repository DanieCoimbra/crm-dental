package crm_data.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.commercial.service.BudgetLookup;
import crm_data.finance.domain.Installment;
import crm_data.finance.domain.InstallmentStatus;
import crm_data.finance.repository.InstallmentRepository;
import crm_data.finance.repository.PaymentRepository;
import crm_data.finance.repository.PaymentRepository.NetPaid;
import crm_data.finance.service.InstallmentService.InstallmentView;
import crm_data.finance.service.InstallmentService.PlanView;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.FieldValidationException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;

class InstallmentServiceTest {

	private final InstallmentRepository installments = mock(InstallmentRepository.class);
	private final PaymentRepository payments = mock(PaymentRepository.class);
	private final BudgetLookup budgets = mock(BudgetLookup.class);
	private final PatientLookup patients = mock(PatientLookup.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final EntityManager entityManager = mock(EntityManager.class);
	private final InstallmentService service = new InstallmentService(installments, payments, budgets, patients, events, entityManager);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID budgetId = UUID.randomUUID();
	private final UUID patientId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		TenantContext.set(clinicId, userId, Role.DENTIST_ADMIN);
		when(installments.saveAndFlush(any(Installment.class))).thenAnswer(call -> call.getArgument(0));
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private Installment stored(int number, String amount, InstallmentStatus status, long version) {
		Installment installment = new Installment(UUID.randomUUID(), budgetId, patientId, number, new BigDecimal(amount),
				LocalDate.parse("2026-12-05"), userId);
		ReflectionTestUtils.setField(installment, "status", status);
		ReflectionTestUtils.setField(installment, "version", version);
		return installment;
	}

	record Row(UUID installmentId, BigDecimal net) implements NetPaid {
		@Override
		public UUID getInstallmentId() {
			return installmentId;
		}

		@Override
		public BigDecimal getNet() {
			return net;
		}
	}

	private static NetPaid net(UUID id, String value) {
		return new Row(id, new BigDecimal(value));
	}

	@Test
	void listComputesNetPaidBalanceAndTheActiveSumWithOneAggregateQuery() {
		Installment paid = stored(1, "300.00", InstallmentStatus.PAID, 1);
		Installment partial = stored(2, "300.00", InstallmentStatus.OPEN, 1);
		Installment canceled = stored(1, "999.00", InstallmentStatus.CANCELED, 2);
		when(budgets.totalOf(budgetId)).thenReturn(new BigDecimal("600.00"));
		when(installments.findAllByBudgetIdOrderByNumberAscIdAsc(budgetId)).thenReturn(List.of(canceled, paid, partial));
		when(payments.netPaidByInstallmentIds(any())).thenReturn(List.of(net(paid.getId(), "300.00"), net(partial.getId(), "100.00")));

		PlanView plan = service.listByBudget(budgetId);

		assertThat(plan.activeSum()).isEqualByComparingTo("600.00");
		assertThat(plan.installments()).hasSize(3);
		InstallmentView canceledView = plan.installments().get(0);
		assertThat(canceledView.netPaid()).isEqualByComparingTo("0.00");
		assertThat(plan.installments().get(1).balance()).isEqualByComparingTo("0.00");
		assertThat(plan.installments().get(2).balance()).isEqualByComparingTo("200.00");
		verify(payments, times(1)).netPaidByInstallmentIds(any());
	}

	@Test
	void anEmptyPlanSkipsThePaymentQuery() {
		when(budgets.totalOf(budgetId)).thenReturn(new BigDecimal("600.00"));
		when(installments.findAllByBudgetIdOrderByNumberAscIdAsc(budgetId)).thenReturn(List.of());

		PlanView plan = service.listByBudget(budgetId);

		assertThat(plan.installments()).isEmpty();
		assertThat(plan.activeSum()).isEqualByComparingTo("0.00");
		verify(payments, never()).netPaidByInstallmentIds(any());
	}

	@Test
	void changeDueDateUpdatesAnOpenInstallmentAndPublishesTheEvent() {
		Installment installment = stored(1, "300.00", InstallmentStatus.OPEN, 3);
		when(installments.findProjectionById(installment.getId())).thenReturn(Optional.of(installment));
		when(payments.netPaidByInstallmentIds(any())).thenReturn(List.of());

		InstallmentView view = service.changeDueDate(installment.getId(), LocalDate.parse("2027-01-15"), 3L);

		assertThat(view.installment().getDueDate()).isEqualTo(LocalDate.parse("2027-01-15"));
		verify(installments).saveAndFlush(installment);
		verify(events).publishEvent(new FinanceEvents.DueDateChanged(clinicId, installment.getId(), userId));
	}

	@Test
	void paidOrCanceledInstallmentsKeepTheirDueDate() {
		Installment paid = stored(1, "300.00", InstallmentStatus.PAID, 3);
		Installment canceled = stored(2, "300.00", InstallmentStatus.CANCELED, 3);
		when(installments.findProjectionById(paid.getId())).thenReturn(Optional.of(paid));
		when(installments.findProjectionById(canceled.getId())).thenReturn(Optional.of(canceled));

		assertThatThrownBy(() -> service.changeDueDate(paid.getId(), LocalDate.parse("2027-01-15"), 3L))
				.isInstanceOf(BusinessException.class).hasMessage(ConflictMessages.INSTALLMENT_NOT_OPEN);
		assertThatThrownBy(() -> service.changeDueDate(canceled.getId(), LocalDate.parse("2027-01-15"), 3L))
				.isInstanceOf(BusinessException.class).hasMessage(ConflictMessages.INSTALLMENT_NOT_OPEN);

		assertThat(paid.getDueDate()).isEqualTo(LocalDate.parse("2026-12-05"));
		verify(installments, never()).saveAndFlush(any());
	}

	@Test
	void aStaleVersionAnUnknownInstallmentAndAnAnonymizedPatientAreRefused() {
		Installment installment = stored(1, "300.00", InstallmentStatus.OPEN, 3);
		when(installments.findProjectionById(installment.getId())).thenReturn(Optional.of(installment));
		UUID unknown = UUID.randomUUID();
		when(installments.findProjectionById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.changeDueDate(installment.getId(), LocalDate.parse("2027-01-15"), 2L))
				.isInstanceOf(BusinessException.class).hasMessage(ConflictMessages.STALE_VERSION);
		assertThatThrownBy(() -> service.changeDueDate(unknown, LocalDate.parse("2027-01-15"), 0L))
				.isInstanceOf(BusinessException.class).hasMessageContaining("não encontrada");

		doThrow(new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado.")).when(patients).requireWritable(patientId);
		assertThatThrownBy(() -> service.changeDueDate(installment.getId(), LocalDate.parse("2027-01-15"), 3L))
				.isInstanceOf(BusinessException.class);
		verify(installments, never()).saveAndFlush(any());
	}

	@Test
	void aMissingDueDateIsAValidationError() {
		assertThatThrownBy(() -> service.changeDueDate(UUID.randomUUID(), null, 0L)).isInstanceOf(FieldValidationException.class);
	}
}
