package crm_data.commercial.domain;

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
 * Orçamento. O banco impõe a integridade do dinheiro: {@code number}, {@code totalAmount}, {@code sentAt} e
 * {@code decidedAt} são preenchidos por trigger (mapeados como somente leitura, e o service relê a linha depois de
 * gravar); itens e conteúdo só mudam em {@code DRAFT}; {@code APPROVED}/{@code REJECTED} são terminais. {@code patientId}
 * e {@code createdBy} são imutáveis. {@code notes} é texto claro, vai no PDF e <strong>não leva dado clínico</strong>.
 * Sem {@code @ToString}: nada de valor, nome ou observação em log.
 */
@Entity
@Table(name = "budgets", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Budget extends TenantAwareEntity {

	@Id
	private UUID id;

	/** Sequencial por clínica, atribuído pelo banco (lock consultivo + {@code max + 1}). */
	@Column(insertable = false, updatable = false)
	private Integer number;

	@Column(name = "patient_id", nullable = false, updatable = false)
	private UUID patientId;

	@Setter
	@Column(name = "appointment_id")
	private UUID appointmentId;

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private BudgetStatus status;

	/** Desconto em valor. O banco só o aceita zero no {@code INSERT} (S7); entra por {@code UPDATE} depois dos itens. */
	@Setter
	@Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal discountAmount;

	/** Σ linhas − desconto, calculado pelo banco; o backend nunca o grava. */
	@Column(name = "total_amount", insertable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal totalAmount;

	@Setter
	@Column(name = "valid_until")
	private LocalDate validUntil;

	@Setter
	@Column(length = 500)
	private String notes;

	@Column(name = "sent_at", insertable = false, updatable = false)
	private Instant sentAt;

	@Column(name = "decided_at", insertable = false, updatable = false)
	private Instant decidedAt;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	/** Orçamento novo: nasce {@code DRAFT} e sem desconto (o desconto entra depois dos itens). */
	public Budget(UUID id, UUID patientId, UUID appointmentId, LocalDate validUntil, String notes, UUID createdBy) {
		this.id = id;
		this.patientId = patientId;
		this.appointmentId = appointmentId;
		this.status = BudgetStatus.DRAFT;
		this.discountAmount = BigDecimal.ZERO.setScale(2);
		this.validUntil = validUntil;
		this.notes = notes;
		this.createdBy = createdBy;
	}
}
