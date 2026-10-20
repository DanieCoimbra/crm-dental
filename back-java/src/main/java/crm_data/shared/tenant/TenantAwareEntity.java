package crm_data.shared.tenant;

import java.util.UUID;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;

/**
 * Superclasse de toda entidade de tabela de negócio (com {@code clinic_id}). Dá a primeira barreira de isolamento:
 * o filtro Hibernate {@value #FILTER} acrescenta {@code clinic_id = ?} às consultas, e a RLS do banco é a barreira
 * final. O {@code clinic_id} é imutável e vem sempre do {@link TenantIdProvider}, nunca de DTO.
 *
 * <p>O filtro vale para consultas (JPQL/Criteria); {@code EntityManager.find} por id não o aplica, e quem protege
 * esse caminho é a RLS. SQL nativo contra tabelas de negócio exige revisão.
 */
@MappedSuperclass
@FilterDef(name = TenantAwareEntity.FILTER, defaultCondition = "clinic_id = :" + TenantAwareEntity.PARAM,
		parameters = @ParamDef(name = TenantAwareEntity.PARAM, type = UUID.class))
@Filter(name = TenantAwareEntity.FILTER)
public abstract class TenantAwareEntity {

	public static final String FILTER = "tenantFilter";
	public static final String PARAM = "clinicId";

	/** Imutável: só getter. O valor vem do TenantIdProvider no @PrePersist, nunca de um setter ou DTO. */
	@Getter
	@Column(name = "clinic_id", nullable = false, updatable = false)
	private UUID clinicId;

	@PrePersist
	void assignTenant() {
		UUID current = TenantIdProvider.currentClinicId();
		if (clinicId == null) {
			clinicId = current;
		}
		else if (!clinicId.equals(current)) {
			throw new IllegalStateException("clinic_id da entidade diverge do tenant da transação.");
		}
	}
}
