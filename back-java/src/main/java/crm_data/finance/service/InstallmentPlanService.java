package crm_data.finance.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.clinic.service.ClinicZones;
import crm_data.commercial.service.ApprovedBudget;
import crm_data.commercial.service.BudgetLookup;
import crm_data.finance.domain.Installment;
import crm_data.finance.repository.InstallmentRepository;
import crm_data.finance.service.InstallmentService.PlanView;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Cria e refaz o plano de parcelas de um orçamento {@code APPROVED}. Serializa pela trava da linha do orçamento
 * ({@code ux_inst_budget_number} é a rede para a corrida). A soma das parcelas é conferida pelo banco no
 * <strong>commit</strong> ({@code CR011}, constraint trigger adiado): como {@code @Transactional} confirma depois do
 * método, a falha chega como {@code TransactionSystemException} e o handler global a traduz em 409, sem persistir nada.
 * Refazer cancela as abertas <em>e dá {@code flush}</em> antes de inserir as novas (o Hibernate executaria os
 * {@code INSERT} antes dos {@code UPDATE} e colidiria com o índice parcial dos números reaproveitados).
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class InstallmentPlanService {

	private final BudgetLookup budgets;
	private final PatientLookup patients;
	private final InstallmentRepository installments;
	private final InstallmentService installmentService;
	private final ClinicZones zones;
	private final Clock clock;
	private final ApplicationEventPublisher events;
	private final EntityManager entityManager;

	/**
	 * @throws BusinessException 404; 409 se o orçamento não está aprovado, o paciente está anonimizado ou o plano já existe
	 * @throws crm_data.shared.error.FieldValidationException 400 com o campo e a diferença
	 */
	@Transactional
	public PlanView create(UUID budgetId, PlanRequest request) {
		ApprovedBudget budget = budgets.lockApproved(budgetId);
		patients.requireWritable(budget.patientId());
		if (!installments.findActiveByBudgetId(budgetId).isEmpty()) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.PLAN_EXISTS);
		}
		List<PlannedInstallment> plan = InstallmentPlanner.plan(budget.totalAmount(), request, today());
		persist(budget, plan);

		events.publishEvent(new FinanceEvents.PlanCreated(TenantContext.clinicId(), budgetId, plan.size(), TenantContext.userId()));
		return reloaded(budget);
	}

	/**
	 * @throws BusinessException 404; 409 sem plano para refazer, com pagamento líquido positivo ou paciente anonimizado
	 */
	@Transactional
	public PlanView redo(UUID budgetId, PlanRequest request) {
		ApprovedBudget budget = budgets.lockApproved(budgetId);
		patients.requireWritable(budget.patientId());
		List<Installment> active = installments.findActiveByBudgetId(budgetId);
		if (active.isEmpty()) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.NO_PLAN);
		}
		Map<UUID, BigDecimal> net = installmentService.netPaid(active.stream().map(Installment::getId).toList());
		if (net.values().stream().anyMatch(value -> value.signum() > 0)) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.REVERSE_FIRST);
		}
		List<PlannedInstallment> plan = InstallmentPlanner.plan(budget.totalAmount(), request, today());

		active.forEach(Installment::cancel);
		entityManager.flush();
		persist(budget, plan);

		events.publishEvent(new FinanceEvents.PlanRedone(TenantContext.clinicId(), budgetId, plan.size(), TenantContext.userId()));
		return reloaded(budget);
	}

	private void persist(ApprovedBudget budget, List<PlannedInstallment> plan) {
		UUID userId = TenantContext.userId();
		for (PlannedInstallment p : plan) {
			installments.save(new Installment(UUID.randomUUID(), budget.id(), budget.patientId(), p.number(), p.amount(),
					p.dueDate(), userId));
		}
		entityManager.flush();
	}

	private PlanView reloaded(ApprovedBudget budget) {
		entityManager.flush();
		entityManager.clear();
		return installmentService.planView(budget.id(), budget.totalAmount());
	}

	/** Data civil de hoje no fuso da clínica (a entrada vence "hoje"). */
	private LocalDate today() {
		return LocalDate.now(clock.withZone(zones.zone()));
	}
}
