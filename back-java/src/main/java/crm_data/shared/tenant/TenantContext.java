package crm_data.shared.tenant;

import java.util.Objects;
import java.util.UUID;

import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;

/**
 * Tenant (clínica), usuário e papel da requisição corrente, por thread. É preenchido pelo
 * {@link TenantContextFilter} a partir das claims do token (nunca de corpo, query ou path) e limpo ao fim da
 * requisição. Fora de requisição, use {@link TenantRunner}.
 */
public final class TenantContext {

	record Snapshot(UUID clinicId, UUID userId, Role role) {
	}

	private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

	private TenantContext() {
	}

	public static void set(UUID clinicId, UUID userId, Role role) {
		CURRENT.set(new Snapshot(Objects.requireNonNull(clinicId, "clinicId"), userId, role));
	}

	/** @throws BusinessException {@code unauthorized} (401) se não há tenant no contexto */
	public static UUID clinicId() {
		return require().clinicId();
	}

	/** @throws BusinessException {@code unauthorized} (401) se não há usuário no contexto */
	public static UUID userId() {
		UUID userId = require().userId();
		if (userId == null) {
			throw unauthorized();
		}
		return userId;
	}

	/** @throws BusinessException {@code unauthorized} (401) se não há papel no contexto */
	public static Role role() {
		Role role = require().role();
		if (role == null) {
			throw unauthorized();
		}
		return role;
	}

	public static boolean isPresent() {
		return CURRENT.get() != null;
	}

	public static void clear() {
		CURRENT.remove();
	}

	static Snapshot snapshot() {
		return CURRENT.get();
	}

	static void restore(Snapshot snapshot) {
		if (snapshot == null) {
			CURRENT.remove();
		}
		else {
			CURRENT.set(snapshot);
		}
	}

	private static Snapshot require() {
		Snapshot snapshot = CURRENT.get();
		if (snapshot == null) {
			throw unauthorized();
		}
		return snapshot;
	}

	private static BusinessException unauthorized() {
		return new BusinessException(ProblemTypes.UNAUTHORIZED, "Autenticação necessária.");
	}
}
