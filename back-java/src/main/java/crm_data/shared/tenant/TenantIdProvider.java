package crm_data.shared.tenant;

import java.util.UUID;

import crm_data.shared.security.Role;

/**
 * Único ponto que entrega tenant, papel e usuário à persistência: sempre os do {@link TenantContext}. Papel e usuário
 * são opcionais (fluxos de {@link TenantRunner} não têm); o tenant é obrigatório.
 */
public final class TenantIdProvider {

	private TenantIdProvider() {
	}

	/** @throws crm_data.shared.error.BusinessException {@code unauthorized} (401) se não há tenant */
	public static UUID currentClinicId() {
		return TenantContext.clinicId();
	}

	/** Papel do token, ou {@code null} em fluxos sem usuário autenticado. Nunca lança. */
	public static Role currentRoleOrNull() {
		TenantContext.Snapshot snapshot = TenantContext.snapshot();
		return snapshot != null ? snapshot.role() : null;
	}

	/** Usuário do token, ou {@code null} em fluxos sem usuário autenticado. Nunca lança. */
	public static UUID currentUserIdOrNull() {
		TenantContext.Snapshot snapshot = TenantContext.snapshot();
		return snapshot != null ? snapshot.userId() : null;
	}
}
