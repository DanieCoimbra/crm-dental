package crm_data.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Parcela de um orçamento aprovado. {@code number}, {@code amount}, orçamento e paciente são imutáveis. O vencimento só
 * muda enquanto {@code OPEN}; o {@code status} {@code OPEN}/{@code PAID} é mantido pelo banco a cada pagamento (e
 * incrementa {@code version}); o backend só o muda por {@link #cancel()}. Sem {@code @ToString}: nada de valor em log.
 */
@Entity
@Table(name = "installments", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Installment extends TenantAwareEntity {

	@Id
	private UUID id;

	@Column(name = "budget_id", nullable = false, updatable = false)
	private UUID budgetId;

	@Column(name = "patient_id", nullable = false, updatable = false)
	private UUID patientId;

	@Column(nullable = false, updatable = false)
	private Short number;

	@Column(nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Setter
	@Column(name = "due_date", nullable = false)
	private LocalDate dueDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private InstallmentStatus status;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	public Installment(UUID id, UUID budgetId, UUID patientId, int number, BigDecimal amount, LocalDate dueDate, UUID createdBy) {
		this.id = id;
		this.budgetId = budgetId;
		this.patientId = patientId;
		this.number = (short) number;
		this.amount = amount;
		this.dueDate = dueDate;
		this.status = InstallmentStatus.OPEN;
		this.createdBy = createdBy;
	}

	/** Cancela a parcela (só o banco aceita a partir de {@code OPEN} com líquido pago zero). */
	public void cancel() {
		this.status = InstallmentStatus.CANCELED;
	}
}
