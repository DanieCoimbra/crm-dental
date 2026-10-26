package crm_data.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tipo de procedimento da clínica (nome, preço-base, duração padrão e cor). Nunca é apagado: inativa-se com
 * {@code active}, porque agendamentos e itens de orçamento apontam para ele. Alterar o preço não muda orçamentos
 * existentes (o item guarda uma cópia do preço no momento em que nasce). {@code createdBy} é imutável.
 */
@Entity
@Table(name = "procedure_types", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcedureType extends TenantAwareEntity {

	@Id
	private UUID id;

	@Setter
	@Column(nullable = false, length = 80)
	private String name;

	@Setter
	@Column(name = "base_price", nullable = false, precision = 12, scale = 2)
	private BigDecimal basePrice;

	@Setter
	@Column(name = "default_duration_minutes", nullable = false)
	private Short defaultDurationMinutes;

	@Setter
	@Column(nullable = false, length = 7)
	private String color;

	@Setter
	@Column(nullable = false)
	private boolean active;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	/** Procedimento novo: nasce ativo. */
	public ProcedureType(UUID id, String name, BigDecimal basePrice, short defaultDurationMinutes, String color,
			UUID createdBy) {
		this.id = id;
		this.name = name;
		this.basePrice = basePrice;
		this.defaultDurationMinutes = defaultDurationMinutes;
		this.color = color;
		this.active = true;
		this.createdBy = createdBy;
	}
}
