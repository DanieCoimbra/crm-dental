package crm_data.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import crm_data.clinic.service.ClinicZones;
import crm_data.commercial.service.ApprovedBudget;
import crm_data.commercial.service.BudgetLookup;
import crm_data.finance.domain.Installment;
import crm_data.finance.domain.InstallmentStatus;
import crm_data.finance.repository.InstallmentRepository;
import crm_data.finance.service.InstallmentService.PlanView;
import crm_data.finance.service.PlanRequest.ManualItem;
import crm_data.finance.service.PlanRequest.Mode;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.FieldValidationException;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;

class InstallmentPlanServiceTest {

	private final BudgetLookup budgets = mock(BudgetLookup.class);
	private final PatientLookup patients = mock(PatientLookup.class);
	private final InstallmentRepository installments = mock(InstallmentRepository.class);
	private final InstallmentService installmentService = mock(InstallmentService.class);
	private final ClinicZones zones = mock(ClinicZones.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final EntityManager entityManager = mock(EntityManager.class);
	// 2026-10-27 01:30 UTC = 2026-10-26 22:30 em São Paulo: a entrada vence na data da clínica, não na do servidor
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T01:30:00Z"), ZoneOffset.UTC);
	private final InstallmentPlanService service = new InstallmentPlanService(budgets, patients, installments, installmentService,
			zones, clock, events, entityManager);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID budgetId = UUID.randomUUID();
	private final UUID patientId = UUID.randomUUID();
	private final ApprovedBudget approved = new ApprovedBudget(budgetId, 12, patientId, new BigDecimal("800.00"), Instant.now());
	private final PlanView reloaded = new PlanView(budgetId, new BigDecimal("800.00"), new BigDecimal("800.00"), List.of());

	@BeforeEach
	void setUp() {
		TenantContext.set(clinicId, userId, Role.DENTIST_ADMIN);
		when(zones.zone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
		when(budgets.lockApproved(budgetId)).thenReturn(approved);
		when(installments.save(any(Installment.class))).thenAnswer(call -> call.getArgument(0));
		when(installmentService.planView(budgetId, approved.totalAmount())).thenReturn(reloaded);
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private static PlanRequest auto(int count, String down) {
		return new PlanRequest(Mode.AUTO, count, LocalDate.parse("2026-12-05"), down == null ? null : new BigDecimal(down), null);
	}

	private Installment stored(int number, String amount, InstallmentStatus status) {
		Installment installment = new Installment(UUID.randomUUID(), budgetId, patientId, number, new BigDecimal(amount),
				LocalDate.parse("2026-12-05"), userId);
		if (status == InstallmentStatus.CANCELED) {
			installment.cancel();
		}
		return installment;
	}

	@Test
	void createPersistsOpenInstallmentsAndTheDownPaymentDueDateIsTheClinicDay() {
		PlanView view = service.create(budgetId, auto(4, "200.00"));

		assertThat(view).isSameAs(reloaded);
		ArgumentCaptor<Installment> saved = ArgumentCaptor.forClass(Installment.class);
		verify(installments, org.mockito.Mockito.times(4)).save(saved.capture());
		assertThat(saved.getAllValues()).extracting(Installment::getStatus).containsOnly(InstallmentStatus.OPEN);
		assertThat(saved.getAllValues().get(0).getDueDate()).isEqualTo(LocalDate.parse("2026-10-26"));
		assertThat(saved.getAllValues()).extracting(Installment::getBudgetId).containsOnly(budgetId);
		assertThat(saved.getAllValues()).extracting(Installment::getPatientId).containsOnly(patientId);
		assertThat(saved.getAllValues()).extracting(Installment::getCreatedBy).containsOnly(userId);
		verify(events).publishEvent(new FinanceEvents.PlanCreated(clinicId, budgetId, 4, userId));
	}

	@Test
	void createWithAnExistingActivePlanIs409() {
		when(installments.findActiveByBudgetId(budgetId)).thenReturn(List.of(stored(1, "800.00", InstallmentStatus.OPEN)));

		assertThatThrownBy(() -> service.create(budgetId, auto(2, null))).isInstanceOf(BusinessException.class)
				.hasMessage(ConflictMessages.PLAN_EXISTS);

		verify(installments, never()).save(any());
	}

	@Test
	void canceledInstallmentsDoNotCountAsAPlan() {
		when(installments.findActiveByBudgetId(budgetId)).thenReturn(List.of());

		service.create(budgetId, auto(2, null));

		verify(installments, org.mockito.Mockito.times(2)).save(any());
	}

	@Test
	void createNeedsAWritablePatientAndAnApprovedBudget() {
		doThrow(new BusinessException(crm_data.shared.error.ProblemTypes.CONFLICT, "Paciente anonimizado.")).when(patients)
				.requireWritable(patientId);
		UUID draft = UUID.randomUUID();
		when(budgets.lockApproved(draft)).thenThrow(new BusinessException(crm_data.shared.error.ProblemTypes.CONFLICT, "não aprovado"));

		assertThatThrownBy(() -> service.create(budgetId, auto(2, null))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.create(draft, auto(2, null))).isInstanceOf(BusinessException.class);

		verify(installments, never()).save(any());
	}

	@Test
	void anInvalidPlanPersistsNothing() {
		assertThatThrownBy(() -> service.create(budgetId, auto(0, null))).isInstanceOf(FieldValidationException.class);
		PlanRequest manual = new PlanRequest(Mode.MANUAL, null, null, null,
				List.of(new ManualItem(new BigDecimal("700.00"), LocalDate.parse("2026-12-05"))));
		assertThatThrownBy(() -> service.create(budgetId, manual)).isInstanceOf(FieldValidationException.class);

		verify(installments, never()).save(any());
		verify(events, never()).publishEvent(any());
	}

	@Test
	void redoCancelsTheOpenOnesAndFlushesBeforeInsertingTheNewOnes() {
		Installment first = stored(1, "400.00", InstallmentStatus.OPEN);
		Installment second = stored(2, "400.00", InstallmentStatus.OPEN);
		when(installments.findActiveByBudgetId(budgetId)).thenReturn(List.of(first, second));
		when(installmentService.netPaid(any())).thenReturn(Map.of());

		service.redo(budgetId, auto(3, null));

		assertThat(first.getStatus()).isEqualTo(InstallmentStatus.CANCELED);
		assertThat(second.getStatus()).isEqualTo(InstallmentStatus.CANCELED);
		InOrder order = inOrder(entityManager, installments);
		order.verify(entityManager).flush();
		order.verify(installments, org.mockito.Mockito.times(3)).save(any());
		verify(events).publishEvent(new FinanceEvents.PlanRedone(clinicId, budgetId, 3, userId));
	}

	@Test
	void redoWithoutAPlanIs409() {
		when(installments.findActiveByBudgetId(budgetId)).thenReturn(List.of());

		assertThatThrownBy(() -> service.redo(budgetId, auto(2, null))).isInstanceOf(BusinessException.class)
				.hasMessage(ConflictMessages.NO_PLAN);
	}

	@Test
	void redoWithAnyNetPaymentIs409AndCancelsNothing() {
		Installment first = stored(1, "400.00", InstallmentStatus.OPEN);
		Installment second = stored(2, "400.00", InstallmentStatus.PAID);
		when(installments.findActiveByBudgetId(budgetId)).thenReturn(List.of(first, second));
		when(installmentService.netPaid(any())).thenReturn(Map.of(second.getId(), new BigDecimal("400.00")));

		assertThatThrownBy(() -> service.redo(budgetId, auto(2, null))).isInstanceOf(BusinessException.class)
				.hasMessage(ConflictMessages.REVERSE_FIRST);

		assertThat(first.getStatus()).isEqualTo(InstallmentStatus.OPEN);
		verify(installments, never()).save(any());
	}

	@Test
	void redoWithAnInvalidPlanLeavesTheOldPlanUntouched() {
		Installment first = stored(1, "800.00", InstallmentStatus.OPEN);
		when(installments.findActiveByBudgetId(budgetId)).thenReturn(List.of(first));
		when(installmentService.netPaid(any())).thenReturn(Map.of());

		assertThatThrownBy(() -> service.redo(budgetId, auto(0, null))).isInstanceOf(FieldValidationException.class);

		assertThat(first.getStatus()).isEqualTo(InstallmentStatus.OPEN);
	}
}
