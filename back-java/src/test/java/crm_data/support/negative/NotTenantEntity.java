package crm_data.support.negative;

import java.util.UUID;

/**
 * Classe PROPOSITALMENTE fora da regra (não estende TenantAwareEntity), usada só no auto-teste de
 * {@code TenantEntityRules}. Não é {@code @Entity} para não entrar no mapeamento do Hibernate.
 */
public class NotTenantEntity {

	@SuppressWarnings("unused")
	private UUID id;
}
