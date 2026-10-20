package crm_data.auth.service;

import java.util.UUID;

import crm_data.shared.security.Role;

/** Resposta de registro, login e refresh (contrato da F04, §3). Não contém plano, trial nem expiração de conta. */
public record AuthSession(String accessToken, String refreshToken, String tokenType, long expiresIn, UserSummary user) {

	/** Nunca imprime os tokens: um log acidental da resposta não pode vazar credenciais. */
	@Override
	public String toString() {
		return "AuthSession[user=" + user + ", tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
	}

	public record UserSummary(UUID id, String name, String email, Role role, UUID clinicId) {

		/** Sem nome nem e-mail: dados pessoais não vão para logs acidentais. */
		@Override
		public String toString() {
			return "UserSummary[id=" + id + ", role=" + role + ", clinicId=" + clinicId + "]";
		}
	}
}
