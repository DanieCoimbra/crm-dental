package crm_data.commercial.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.service.BudgetDecisionService;
import crm_data.commercial.service.BudgetService;
import crm_data.commercial.service.BudgetService.BudgetView;
import crm_data.commercial.web.BudgetRequests.ActionRequest;
import crm_data.commercial.web.BudgetRequests.CreateBudgetRequest;
import crm_data.commercial.web.BudgetRequests.UpdateBudgetRequest;
import crm_data.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Orçamento: os dois papéis leem; só o dentista cria, edita e descarta (rota e {@code @PreAuthorize}). Envio, decisão e
 * parcelas são das F04 e F05.
 */
@RestController
@RequestMapping("/api/v1/budgets")
@Validated
@RequiredArgsConstructor
public class BudgetController {

	private final BudgetService budgets;
	private final BudgetDecisionService decisions;

	@PostMapping
	public ResponseEntity<BudgetResponse> create(@Valid @RequestBody CreateBudgetRequest request) {
		BudgetView created = budgets.create(request.patientId(), request.toDraft());
		return ResponseEntity.created(URI.create("/api/v1/budgets/" + created.budget().getId()))
				.body(BudgetResponse.from(created));
	}

	/** {@code status} repetível; {@code number} é o número exato do orçamento na clínica. */
	@GetMapping
	public PageResponse<BudgetSummaryResponse> list(@RequestParam(required = false) List<BudgetStatus> status,
			@RequestParam(required = false) UUID patientId,
			@RequestParam(required = false) @Min(value = 1, message = "deve ser maior ou igual a 1") Integer number,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser maior ou igual a 0") @Max(value = 100000, message = "deve ser no máximo 100000") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "deve estar entre 1 e 50") @Max(value = 50, message = "deve estar entre 1 e 50") int size) {
		return PageResponse.of(budgets.list(status, patientId, number, page, size), BudgetSummaryResponse::from);
	}

	@GetMapping("/{id}")
	public BudgetResponse get(@PathVariable UUID id) {
		return BudgetResponse.from(budgets.get(id));
	}

	@PutMapping("/{id}")
	public BudgetResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateBudgetRequest request) {
		return BudgetResponse.from(budgets.update(id, request.toDraft(), request.version()));
	}

	@PostMapping("/{id}/discard")
	public BudgetResponse discard(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
		return BudgetResponse.from(budgets.discard(id, request.version()));
	}

	/** {@code SENT → APPROVED}; não gera parcelas (o plano é uma chamada à parte). */
	@PostMapping("/{id}/approve")
	public BudgetResponse approve(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
		return BudgetResponse.from(decisions.approve(id, request.version()));
	}

	/** {@code SENT → REJECTED}. Descartar rascunho é {@code discard}. */
	@PostMapping("/{id}/reject")
	public BudgetResponse reject(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
		return BudgetResponse.from(decisions.reject(id, request.version()));
	}
}
