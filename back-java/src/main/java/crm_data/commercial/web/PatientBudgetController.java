package crm_data.commercial.web;

import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.commercial.service.BudgetService;
import crm_data.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** Orçamentos de um paciente (qualquer papel), do mais recente ao mais antigo; só o que o orçamento tem, sem prontuário. */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/budgets")
@Validated
@RequiredArgsConstructor
public class PatientBudgetController {

	private final BudgetService budgets;

	@GetMapping
	public PageResponse<BudgetSummaryResponse> history(@PathVariable UUID patientId,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser maior ou igual a 0") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "deve estar entre 1 e 50") @Max(value = 50, message = "deve estar entre 1 e 50") int size) {
		return PageResponse.of(budgets.historyOf(patientId, page, size), BudgetSummaryResponse::from);
	}
}
