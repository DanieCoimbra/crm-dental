package crm_data.commercial.service;

import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.repository.BudgetRepository;
import crm_data.commercial.service.BudgetService.BudgetView;
import crm_data.commercial.service.BudgetTransitions.Action;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * Decisão do paciente sobre o orçamento enviado: {@code SENT → APPROVED} ou {@code SENT → REJECTED} (só o dentista).
 * <strong>Só vale a partir de {@code SENT}</strong> (H3): o banco aceitaria {@code DRAFT → APPROVED}, mas o backend recusa,
 * porque sem o envio não existe o PDF que prova o que foi oferecido; descartar um rascunho é a rota {@code discard} do
 * {@link BudgetService}. {@code APPROVED} e {@code REJECTED} são terminais. Aprovar <em>não</em> gera parcelas: o plano é
 * uma chamada à parte do financeiro. O banco carimba {@code decided_at}.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class BudgetDecisionService {

	private final BudgetRepository budgets;
	private final BudgetService budgetService;
	private final ApplicationEventPublisher events;

	/** @throws BusinessException 404; 409 com {@code version} defasada ou fora de {@code SENT} (mensagem própria para {@code DRAFT}) */
	@Transactional
	public BudgetView approve(UUID id, long expectedVersion) {
		return decide(id, expectedVersion, Action.APPROVE);
	}

	/** @throws BusinessException 404; 409 com {@code version} defasada ou fora de {@code SENT} ("Use descartar para rascunhos.") */
	@Transactional
	public BudgetView reject(UUID id, long expectedVersion) {
		return decide(id, expectedVersion, Action.REJECT);
	}

	private BudgetView decide(UUID id, long expectedVersion, Action action) {
		Budget budget = budgets.findForUpdateById(id)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Orçamento não encontrado."));
		if (!Objects.equals(budget.getVersion(), expectedVersion)) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION);
		}
		if (!BudgetTransitions.allowedFrom(action, budget.getStatus())) {
			throw new BusinessException(ProblemTypes.CONFLICT, BudgetTransitions.refusal(action, budget.getStatus()));
		}
		BudgetStatus target = BudgetTransitions.target(action);
		budget.setStatus(target);
		budgets.saveAndFlush(budget);

		events.publishEvent(new CommercialEvents.BudgetDecided(TenantContext.clinicId(), id, target.name(), TenantContext.userId()));
		return budgetService.reloaded(id);
	}
}
