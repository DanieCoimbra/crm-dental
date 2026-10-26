package crm_data.commercial.domain;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Item do orçamento. Imutável: editar o rascunho <em>substitui</em> a lista inteira (a linha nunca sofre {@code UPDATE}).
 * {@code description} e {@code unitPrice} são copiados do catálogo pelo banco no {@code INSERT} (snapshot: mudar o preço
 * do procedimento depois não altera o orçamento) e {@code lineTotal} é coluna gerada; os três são somente leitura e o
 * service relê os itens depois de gravar. Sem {@code @Version}: implementa {@link Persistable} para o {@code save}
 * fazer {@code persist} (sem {@code SELECT} prévio). {@code version} e {@code updated_at} não são mapeados.
 */
@Entity
@Immutable
@Table(name = "budget_items", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetItem extends TenantAwareEntity implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "budget_id", nullable = false, updatable = false)
	private UUID budgetId;

	@Column(name = "procedure_type_id", nullable = false, updatable = false)
	private UUID procedureTypeId;

	@Column(insertable = false, updatable = false, length = 120)
	private String description;

	@Column(nullable = false)
	private Short quantity;

	@Column(name = "unit_price", insertable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal unitPrice;

	@Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal discountAmount;

	@Column(name = "line_total", insertable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal lineTotal;

	@Column(nullable = false)
	private Short position;

	@Transient
	private boolean isNew;

	public BudgetItem(UUID id, UUID budgetId, UUID procedureTypeId, short quantity, BigDecimal discountAmount, short position) {
		this.id = id;
		this.budgetId = budgetId;
		this.procedureTypeId = procedureTypeId;
		this.quantity = quantity;
		this.discountAmount = discountAmount;
		this.position = position;
		this.isNew = true;
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
