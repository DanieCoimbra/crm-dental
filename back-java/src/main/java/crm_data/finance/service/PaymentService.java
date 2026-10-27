package crm_data.finance.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.clinic.service.ClinicZones;
import crm_data.finance.domain.Payment;
import crm_data.finance.domain.PaymentKind;
import crm_data.finance.domain.PaymentMethod;
import crm_data.finance.repository.InstallmentRepository;
import crm_data.finance.repository.InstallmentRow;
import crm_data.finance.repository.PaymentRepository;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Pagamentos e estornos (só o dentista). O banco é a autoridade do saldo: sob a trava da parcela ele confere o valor
 * ({@code CR010} → 409), mantém {@code OPEN}/{@code PAID} e incrementa a versão; aqui o backend só valida o formato,
 * resolve a data na clínica e relê a parcela <strong>por projeção</strong> (a entidade {@code Installment} nunca é
 * carregada, porque o trigger a muda por baixo). Não existe {@code UPDATE} nem {@code DELETE} de pagamento: o estorno é
 * uma nova linha {@code REVERSAL}, total, com a data de hoje. Sem idempotência: dois envios iguais geram dois
 * lançamentos se couberem no saldo.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class PaymentService {

	/** Lançamento e a parcela como ficou depois dele. */
	public record PaymentResult(Payment payment, InstallmentRow installment, BigDecimal netPaid) {

		public BigDecimal balance() {
			return installment.amount().subtract(netPaid);
		}
	}

	private final PaymentRepository payments;
	private final InstallmentRepository installments;
	private final PatientLookup patients;
	private final ClinicZones zones;
	private final Clock clock;
	private final ApplicationEventPublisher events;
	private final EntityManager entityManager;

	/**
	 * @param paidOn data do recebimento; {@code null} = hoje na clínica
	 * @throws BusinessException 404 parcela; 409 acima do saldo, parcela cancelada ou paciente anonimizado; 422
	 *         {@code FUTURE_DATE}
	 */
	@Transactional
	public PaymentResult register(UUID installmentId, BigDecimal amount, PaymentMethod method, LocalDate paidOn) {
		InstallmentRow row = installments.findRowById(installmentId).orElseThrow(PaymentService::installmentNotFound);
		patients.requireWritable(row.patientId());
		LocalDate today = today();
		LocalDate date = paidOn == null ? today : paidOn;
		if (date.isAfter(today)) {
			throw new BusinessException(ProblemTypes.BUSINESS_RULE, "A data do pagamento não pode ser futura.", "FUTURE_DATE");
		}
		Payment payment = payments.save(Payment.payment(installmentId, amount, method, date, TenantContext.userId()));
		entityManager.flush(); // o banco confere o saldo aqui (CR010) e atualiza a parcela

		events.publishEvent(new FinanceEvents.PaymentRegistered(TenantContext.clinicId(), payment.getId(), installmentId,
				TenantContext.userId()));
		return result(payment.getId(), installmentId);
	}

	/**
	 * Estorno total de um pagamento.
	 *
	 * @throws BusinessException 404; 409 se o alvo é um estorno, já foi estornado ou o paciente está anonimizado
	 */
	@Transactional
	public PaymentResult reverse(UUID paymentId) {
		Payment original = payments.findOwnById(paymentId)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Pagamento não encontrado."));
		if (original.getKind() != PaymentKind.PAYMENT) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.REVERSAL_OF_REVERSAL);
		}
		if (payments.existsByReversesPaymentId(paymentId)) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.PAYMENT_REVERSED);
		}
		InstallmentRow row = installments.findRowById(original.getInstallmentId()).orElseThrow(PaymentService::installmentNotFound);
		patients.requireWritable(row.patientId());

		Payment reversal = payments.save(Payment.reversalOf(original, today(), TenantContext.userId()));
		entityManager.flush(); // ux_pay_reverses é a rede da corrida de dois estornos

		events.publishEvent(new FinanceEvents.PaymentReversed(TenantContext.clinicId(), reversal.getId(), paymentId,
				original.getInstallmentId(), TenantContext.userId()));
		return result(reversal.getId(), original.getInstallmentId());
	}

	/** Pagamentos e estornos da parcela em ordem cronológica. */
	@Transactional(readOnly = true)
	public List<Payment> list(UUID installmentId) {
		installments.findRowById(installmentId).orElseThrow(PaymentService::installmentNotFound);
		return payments.findByInstallmentIdOrderByCreatedAtAscIdAsc(installmentId);
	}

	/** Relê o lançamento (o banco carimba {@code created_at}) e a parcela depois do trigger. */
	private PaymentResult result(UUID paymentId, UUID installmentId) {
		entityManager.clear();
		Payment payment = payments.findOwnById(paymentId).orElseThrow();
		InstallmentRow row = installments.findRowById(installmentId).orElseThrow(PaymentService::installmentNotFound);
		Map<UUID, BigDecimal> net = netPaid(installmentId);
		return new PaymentResult(payment, row, net.getOrDefault(installmentId, BigDecimal.ZERO.setScale(2)));
	}

	private Map<UUID, BigDecimal> netPaid(UUID installmentId) {
		Map<UUID, BigDecimal> map = new java.util.HashMap<>();
		payments.netPaidByInstallmentIds(List.of(installmentId)).forEach(row -> map.put(row.getInstallmentId(), row.getNet()));
		return map;
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(zones.zone()));
	}

	private static BusinessException installmentNotFound() {
		return new BusinessException(ProblemTypes.NOT_FOUND, "Parcela não encontrada.");
	}
}
