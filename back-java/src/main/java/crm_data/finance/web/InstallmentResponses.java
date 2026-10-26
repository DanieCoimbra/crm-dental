package crm_data.finance.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import crm_data.finance.domain.Installment;
import crm_data.finance.domain.InstallmentStatus;
import crm_data.finance.service.InstallmentService.InstallmentView;
import crm_data.finance.service.InstallmentService.PlanView;
import crm_data.shared.money.Money;

/** Respostas do plano de parcelas. Dinheiro em texto decimal; nunca traz {@code clinicId} nem o paciente. */
public final class InstallmentResponses {

	private InstallmentResponses() {
	}

	public record InstallmentResponse(UUID id, int number, String amount, LocalDate dueDate, InstallmentStatus status,
			String netPaid, String balance, Long version) {

		public static InstallmentResponse from(InstallmentView view) {
			Installment i = view.installment();
			return new InstallmentResponse(i.getId(), i.getNumber(), Money.format(i.getAmount()), i.getDueDate(), i.getStatus(),
					Money.format(view.netPaid()), Money.format(view.balance()), i.getVersion());
		}
	}

	public record InstallmentPlanResponse(UUID budgetId, String totalAmount, String activeSum,
			List<InstallmentResponse> installments) {

		public static InstallmentPlanResponse from(PlanView plan) {
			return new InstallmentPlanResponse(plan.budgetId(), Money.format(plan.totalAmount()),
					Money.format(plan.activeSum()), plan.installments().stream().map(InstallmentResponse::from).toList());
		}
	}
}
