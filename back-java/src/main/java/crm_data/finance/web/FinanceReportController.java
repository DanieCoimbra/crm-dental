package crm_data.finance.web;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.finance.service.FinanceReportService;
import crm_data.finance.service.FinanceRules;
import crm_data.finance.service.PatientFinancialService;
import crm_data.finance.web.FinanceReportResponses.CashFlowResponse;
import crm_data.finance.web.FinanceReportResponses.PatientFinancialResponse;
import crm_data.finance.web.FinanceReportResponses.ReceivablesResponse;
import crm_data.finance.web.FinanceReportResponses.RevenueResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Consultas do financeiro e ficha do paciente: só o dentista (rotas e {@code @PreAuthorize}). As datas são locais da
 * clínica ({@code AAAA-MM-DD}); {@code from}/{@code to} ausentes viram 400 no serviço, com o campo no {@code errors[]}.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
public class FinanceReportController {

	private static final String PAGE_MIN = "deve ser maior ou igual a 0";
	private static final String SIZE_RANGE = "deve estar entre 1 e " + FinanceRules.MAX_PAGE_SIZE;

	private final FinanceReportService reports;
	private final PatientFinancialService patientFinancial;

	@GetMapping("/finance/receivables")
	public ReceivablesResponse receivables(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = PAGE_MIN) @Max(value = 100000, message = "deve ser no máximo 100000") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = SIZE_RANGE) @Max(value = FinanceRules.MAX_PAGE_SIZE, message = SIZE_RANGE) int size) {
		return ReceivablesResponse.from(reports.receivables(from, to, page, size));
	}

	@GetMapping("/finance/overdue")
	public ReceivablesResponse overdue(@RequestParam(defaultValue = "0") @Min(value = 0, message = PAGE_MIN) @Max(value = 100000, message = "deve ser no máximo 100000") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = SIZE_RANGE) @Max(value = FinanceRules.MAX_PAGE_SIZE, message = SIZE_RANGE) int size) {
		return ReceivablesResponse.from(reports.overdue(page, size));
	}

	@GetMapping("/finance/cash-flow")
	public CashFlowResponse cashFlow(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
		return CashFlowResponse.from(reports.cashFlow(from, to));
	}

	@GetMapping("/finance/revenue")
	public RevenueResponse revenue(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
		return RevenueResponse.from(reports.revenue(from, to));
	}

	@GetMapping("/patients/{patientId}/financial")
	public PatientFinancialResponse financial(@PathVariable UUID patientId) {
		return PatientFinancialResponse.from(patientFinancial.get(patientId));
	}
}
