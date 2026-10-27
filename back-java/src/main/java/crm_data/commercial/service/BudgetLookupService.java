package crm_data.commercial.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.repository.BudgetRepository;
import crm_data.commercial.service.BudgetTransitions.Action;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class BudgetLookupService implements BudgetLookup {

	private final BudgetRepository budgets;

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, BudgetBrief> brief(Collection<UUID> budgetIds) {
		if (budgetIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, BudgetBrief> briefs = new HashMap<>();
		for (Budget budget : budgets.findAllByIds(budgetIds)) {
			briefs.put(budget.getId(), new BudgetBrief(budget.getId(), budget.getNumber(), budget.getPatientId()));
		}
		return briefs;
	}

	@Override
	@Transactional(readOnly = true)
	public BigDecimal totalOf(UUID budgetId) {
		return budgets.findOwnById(budgetId).orElseThrow(BudgetLookupService::notFound).getTotalAmount();
	}

	@Override
	@Transactional(readOnly = true)
	public ApprovedBudget requireApproved(UUID budgetId) {
		return approved(budgets.findOwnById(budgetId).orElseThrow(BudgetLookupService::notFound));
	}

	@Override
	@Transactional
	public ApprovedBudget lockApproved(UUID budgetId) {
		return approved(budgets.findForUpdateById(budgetId).orElseThrow(BudgetLookupService::notFound));
	}

	@Override
	@Transactional(readOnly = true)
	public List<ApprovedBudget> approvedBetween(Instant ini, Instant fim, int limit) {
		return budgets.findApprovedBetween(ini, fim, PageRequest.of(0, limit)).stream().map(BudgetLookupService::toApproved).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public long approvedCount(Instant ini, Instant fim) {
		return budgets.countApprovedBetween(ini, fim);
	}

	@Override
	@Transactional(readOnly = true)
	public BigDecimal approvedTotal(Instant ini, Instant fim) {
		return budgets.sumApprovedBetween(ini, fim);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ApprovedBudget> approvedOfPatient(UUID patientId, int limit) {
		return budgets.findApprovedOfPatient(patientId, PageRequest.of(0, limit)).stream().map(BudgetLookupService::toApproved).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public BigDecimal approvedTotalOfPatient(UUID patientId) {
		return budgets.sumApprovedOfPatient(patientId);
	}

	private static ApprovedBudget toApproved(crm_data.commercial.repository.ApprovedRow row) {
		return new ApprovedBudget(row.getId(), row.getNumber(), row.getPatientId(), row.getTotalAmount(), row.getDecidedAt());
	}

	private static ApprovedBudget approved(Budget budget) {
		if (budget.getStatus() != BudgetStatus.APPROVED) {
			throw new BusinessException(ProblemTypes.CONFLICT, BudgetTransitions.refusal(Action.PLAN, budget.getStatus()));
		}
		return new ApprovedBudget(budget.getId(), budget.getNumber(), budget.getPatientId(), budget.getTotalAmount(),
				budget.getDecidedAt());
	}

	private static BusinessException notFound() {
		return new BusinessException(ProblemTypes.NOT_FOUND, "Orçamento não encontrado.");
	}
}
