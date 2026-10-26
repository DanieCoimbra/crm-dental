package crm_data.finance.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import crm_data.finance.domain.InstallmentStatus;
import crm_data.finance.domain.Payment;
import crm_data.finance.domain.PaymentKind;
import crm_data.finance.domain.PaymentMethod;
import crm_data.finance.service.PaymentService.PaymentResult;
import crm_data.shared.money.Money;

/** Respostas de pagamento. Dinheiro em texto decimal; nunca traz {@code clinicId}. */
public final class PaymentResponses {

	private PaymentResponses() {
	}

	public record PaymentResponse(UUID id, PaymentKind kind, String amount, PaymentMethod method, LocalDate paidOn,
			UUID reversesPaymentId, UUID createdBy, Instant createdAt) {

		public static PaymentResponse from(Payment p) {
			return new PaymentResponse(p.getId(), p.getKind(), Money.format(p.getAmount()), p.getMethod(), p.getPaidOn(),
					p.getReversesPaymentId(), p.getCreatedBy(), p.getCreatedAt());
		}
	}

	/** A parcela como ficou depois do lançamento. */
	public record InstallmentState(UUID id, InstallmentStatus status, String netPaid, String balance, long version) {
	}

	public record PaymentResultResponse(PaymentResponse payment, InstallmentState installment) {

		public static PaymentResultResponse from(PaymentResult result) {
			return new PaymentResultResponse(PaymentResponse.from(result.payment()),
					new InstallmentState(result.installment().id(), result.installment().status(), Money.format(result.netPaid()),
							Money.format(result.balance()), result.installment().version()));
		}
	}
}
