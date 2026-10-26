package crm_data.finance.web;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import crm_data.finance.service.InstallmentPlanService;
import crm_data.finance.service.InstallmentService;
import crm_data.finance.web.FinanceRequests.DueDateBody;
import crm_data.finance.web.FinanceRequests.PlanBody;
import crm_data.finance.web.InstallmentResponses.InstallmentPlanResponse;
import crm_data.finance.web.InstallmentResponses.InstallmentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Plano de parcelas: só o dentista (rotas e {@code @PreAuthorize} dos serviços). */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InstallmentController {

	private final InstallmentPlanService plans;
	private final InstallmentService installments;

	@GetMapping("/budgets/{budgetId}/installments")
	public InstallmentPlanResponse list(@PathVariable UUID budgetId) {
		return InstallmentPlanResponse.from(installments.listByBudget(budgetId));
	}

	@PostMapping("/budgets/{budgetId}/installments")
	public ResponseEntity<InstallmentPlanResponse> create(@PathVariable UUID budgetId, @Valid @RequestBody PlanBody body) {
		InstallmentPlanResponse response = InstallmentPlanResponse.from(plans.create(budgetId, body.toRequest()));
		return ResponseEntity.created(URI.create("/api/v1/budgets/" + budgetId + "/installments")).body(response);
	}

	/** Refaz o plano: cancela as parcelas abertas sem pagamento e gera as novas, na mesma transação. */
	@PutMapping("/budgets/{budgetId}/installments")
	public InstallmentPlanResponse redo(@PathVariable UUID budgetId, @Valid @RequestBody PlanBody body) {
		return InstallmentPlanResponse.from(plans.redo(budgetId, body.toRequest()));
	}

	@PatchMapping("/installments/{id}/due-date")
	public InstallmentResponse changeDueDate(@PathVariable UUID id, @Valid @RequestBody DueDateBody body) {
		return InstallmentResponse.from(installments.changeDueDate(id, body.dueDate(), body.version()));
	}
}
