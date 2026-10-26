package crm_data.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Pagamento ou estorno: linha só de inserção (o banco nega {@code UPDATE} e {@code DELETE} a qualquer papel). Sem
 * {@code @Version}, sem {@code updated_at} e sem setters: o estorno é uma nova linha {@code REVERSAL}. O banco trava a
 * parcela, confere o saldo e mantém o status e a versão dela; por isso a entidade {@code Installment} nunca é carregada
 * neste fluxo. Implementa {@link Persistable} para o {@code save} fazer {@code persist} sem {@code SELECT} prévio. Sem
 * {@code @ToString}: nada de valor em log.
 */
@Entity
@Immutable
@Table(name = "payments", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends TenantAwareEntity implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "installment_id", nullable = false, updatable = false)
	private UUID installmentId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 10)
	private PaymentKind kind;

	@Column(nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 15)
	private PaymentMethod method;

	@Column(name = "paid_on", nullable = false, updatable = false)
	private LocalDate paidOn;

	@Column(name = "reverses_payment_id", updatable = false)
	private UUID reversesPaymentId;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew;

	private Payment(UUID id, UUID installmentId, PaymentKind kind, BigDecimal amount, PaymentMethod method, LocalDate paidOn,
			UUID reversesPaymentId, UUID createdBy) {
		this.id = id;
		this.installmentId = installmentId;
		this.kind = kind;
		this.amount = amount;
		this.method = method;
		this.paidOn = paidOn;
		this.reversesPaymentId = reversesPaymentId;
		this.createdBy = createdBy;
		this.isNew = true;
	}

	public static Payment payment(UUID installmentId, BigDecimal amount, PaymentMethod method, LocalDate paidOn, UUID createdBy) {
		return new Payment(UUID.randomUUID(), installmentId, PaymentKind.PAYMENT, amount, method, paidOn, null, createdBy);
	}

	/** Estorno total: mesmo valor, forma e parcela do pagamento estornado. */
	public static Payment reversalOf(Payment original, LocalDate paidOn, UUID createdBy) {
		return new Payment(UUID.randomUUID(), original.getInstallmentId(), PaymentKind.REVERSAL, original.getAmount(),
				original.getMethod(), paidOn, original.getId(), createdBy);
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}
}
