package crm_data.auth.service;

import java.util.UUID;

/** Eventos internos, em memória, publicados pelo módulo de autenticação (sem tokens nem dados pessoais). */
public final class AuthEvents {

	private AuthEvents() {
	}

	/** {@code Auth.ClinicRegistered}: registro concluído. */
	public record ClinicRegistered(UUID clinicId, UUID userId) {
	}

	/** {@code Auth.RefreshReuseDetected}: um refresh token já usado foi reapresentado; a família foi revogada. */
	public record RefreshReuseDetected(UUID userId, UUID familyId) {
	}

	/** {@code Auth.UserSessionsRevoked}: sessões do usuário revogadas (troca de senha, desativação, reset: F05). */
	public record UserSessionsRevoked(UUID userId) {
	}
}
