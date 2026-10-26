package crm_data.finance.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import crm_data.finance.service.PaymentService;
import crm_data.finance.service.PaymentService.PaymentResult;
import crm_data.finance.web.PaymentRequests.PaymentRequest;
import crm_data.finance.web.PaymentResponses.PaymentResponse;
import crm_data.finance.web.PaymentResponses.PaymentResultResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Pagamentos e estornos: só o dentista (rotas e {@code @PreAuthorize}). Não existe {@code PUT}, {@code PATCH} nem
 * {@code DELETE} de pagamento.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PaymentController {

	private final PaymentService payments;

	@GetMapping("/installments/{installmentId}/payments")
	public List<PaymentResponse> list(@PathVariable UUID installmentId) {
		return payments.list(installmentId).stream().map(PaymentResponse::from).toList();
	}

	@PostMapping("/installments/{installmentId}/payments")
	public ResponseEntity<PaymentResultResponse> register(@PathVariable UUID installmentId, @Valid @RequestBody PaymentRequest request) {
		PaymentResult result = payments.register(installmentId, request.parsedAmount(), request.method(), request.paidOn());
		return created(result);
	}

	/** Estorno total: nova linha {@code REVERSAL}, sem corpo. */
	@PostMapping("/payments/{paymentId}/reversal")
	public ResponseEntity<PaymentResultResponse> reverse(@PathVariable UUID paymentId) {
		return created(payments.reverse(paymentId));
	}

	private static ResponseEntity<PaymentResultResponse> created(PaymentResult result) {
		return ResponseEntity.created(URI.create("/api/v1/payments/" + result.payment().getId()))
				.body(PaymentResultResponse.from(result));
	}
}
