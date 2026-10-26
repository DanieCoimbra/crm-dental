package crm_data.finance.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import crm_data.commercial.service.ApprovedBudget;
import crm_data.commercial.service.BudgetBrief;
import crm_data.finance.domain.InstallmentStatus;
import crm_data.finance.domain.PaymentMethod;
import crm_data.finance.service.FinanceReportService.CashFlow;
import crm_data.finance.service.FinanceReportService.Receivable;
import crm_data.finance.service.FinanceReportService.ReceivablePage;
import crm_data.finance.service.FinanceReportService.Revenue;
import crm_data.finance.service.PatientFinancialService.InstallmentLine;
import crm_data.finance.service.PatientFinancialService.PatientFinancial;
import crm_data.patient.service.PatientBrief;
import crm_data.shared.money.Money;

/** Respostas do financeiro. Dinheiro em texto decimal; nunca traz {@code clinicId}. */
public final class FinanceReportResponses {

	private FinanceReportResponses() {
	}

	public record PatientRef(UUID id, String name) {

		static PatientRef from(PatientBrief brief) {
			return brief == null ? null : new PatientRef(brief.id(), brief.name());
		}
	}

	public record BudgetRef(UUID id, int number) {

		static BudgetRef from(BudgetBrief brief) {
			return brief == null ? null : new BudgetRef(brief.id(), brief.number());
		}
	}

	/** {@code daysOverdue} só aparece na inadimplência. */
	public record ReceivableResponse(UUID installmentId, int number, String amount, LocalDate dueDate, String netPaid, String balance,
			PatientRef patient, BudgetRef budget, Long daysOverdue) {

		static ReceivableResponse from(Receivable r) {
			return new ReceivableResponse(r.installment().id(), r.installment().number(), Money.format(r.installment().amount()),
					r.installment().dueDate(), Money.format(r.netPaid()), Money.format(r.balance()), PatientRef.from(r.patient()),
					BudgetRef.from(r.budget()), r.daysOverdue());
		}
	}

	public record Summary(long count, String balanceTotal) {
	}

	public record ReceivablesResponse(List<ReceivableResponse> content, int page, int size, long totalElements, int totalPages,
			Summary summary) {

		public static ReceivablesResponse from(ReceivablePage result) {
			return new ReceivablesResponse(result.page().getContent().stream().map(ReceivableResponse::from).toList(),
					result.page().getNumber(), result.page().getSize(), result.page().getTotalElements(),
					result.page().getTotalPages(), new Summary(result.count(), Money.format(result.balanceTotal())));
		}
	}

	public record DayNetResponse(LocalDate date, String net) {
	}

	public record MethodNetResponse(PaymentMethod method, String net) {
	}

	public record CashFlowResponse(LocalDate from, LocalDate to, String netTotal, List<DayNetResponse> byDay,
			List<MethodNetResponse> byMethod) {

		public static CashFlowResponse from(CashFlow flow) {
			return new CashFlowResponse(flow.from(), flow.to(), Money.format(flow.netTotal()),
					flow.byDay().stream().map(d -> new DayNetResponse(d.date(), Money.format(d.net()))).toList(),
					flow.byMethod().stream().map(m -> new MethodNetResponse(m.method(), Money.format(m.net()))).toList());
		}
	}

	public record RevenueBudget(UUID id, int number, PatientRef patient, String totalAmount, Instant decidedAt) {
	}

	public record RevenueResponse(LocalDate from, LocalDate to, long count, String totalAmount, List<RevenueBudget> budgets,
			boolean truncated) {

		public static RevenueResponse from(Revenue revenue) {
			return new RevenueResponse(revenue.from(), revenue.to(), revenue.count(), Money.format(revenue.totalAmount()),
					revenue.budgets().stream().map(i -> new RevenueBudget(i.budget().id(), i.budget().number(),
							PatientRef.from(i.patient()), Money.format(i.budget().totalAmount()), i.budget().decidedAt())).toList(),
					revenue.truncated());
		}
	}

	public record FinancialBudget(UUID id, int number, String totalAmount, Instant decidedAt) {

		static FinancialBudget from(ApprovedBudget b) {
			return new FinancialBudget(b.id(), b.number(), Money.format(b.totalAmount()), b.decidedAt());
		}
	}

	public record FinancialInstallment(UUID id, UUID budgetId, int number, String amount, LocalDate dueDate, InstallmentStatus status,
			String netPaid, String balance, long version) {

		static FinancialInstallment from(InstallmentLine line) {
			return new FinancialInstallment(line.installment().id(), line.installment().budgetId(), line.installment().number(),
					Money.format(line.installment().amount()), line.installment().dueDate(), line.installment().status(),
					Money.format(line.netPaid()), Money.format(line.balance()), line.installment().version());
		}
	}

	public record PatientFinancialResponse(UUID patientId, String approvedTotal, String activeInstallmentsTotal, String paid,
			String balance, String overdue, List<FinancialBudget> budgets, List<FinancialInstallment> installments,
			boolean truncated) {

		public static PatientFinancialResponse from(PatientFinancial f) {
			return new PatientFinancialResponse(f.patientId(), Money.format(f.approvedTotal()),
					Money.format(f.activeInstallmentsTotal()), Money.format(f.paid()), Money.format(f.balance()),
					Money.format(f.overdue()), f.budgets().stream().map(FinancialBudget::from).toList(),
					f.installments().stream().map(FinancialInstallment::from).toList(), f.truncated());
		}
	}
}
