package crm_data.shared.security;

import java.util.UUID;

import crm_data.shared.tenant.TenantContext;

/**
 * Identidade autenticada da requisição, a mesma do {@link TenantContext} (preenchido pelo filtro a partir das
 * claims do access token). Serve às features autenticadas; nunca vem de corpo, query ou path.
 */
public record TokenPrincipal(UUID userId, UUID clinicId, Role role) {

	/** @throws crm_data.shared.error.BusinessException {@code unauthorized} (401) se não há usuário autenticado */
	public static TokenPrincipal current() {
		return new TokenPrincipal(TenantContext.userId(), TenantContext.clinicId(), TenantContext.role());
	}
}
