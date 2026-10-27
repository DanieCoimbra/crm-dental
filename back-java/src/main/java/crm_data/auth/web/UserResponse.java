package crm_data.auth.web;

import java.util.UUID;

import crm_data.auth.domain.User;
import crm_data.shared.security.Role;

/**
 * Usuário na API (contrato da F05, §3). Nunca contém o hash da senha: ele nem existe neste tipo. {@code cro} e
 * {@code croState} (Fase 4) são nulos para a recepção e para o dentista sem registro; {@code version} serve ao
 * controle otimista de {@code PUT /me/professional-registry}.
 */
public record UserResponse(UUID id, String name, String email, Role role, boolean active, UUID clinicId, String cro,
		String croState, Long version) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.isActive(),
				user.getClinicId(), user.getCroNumber(), user.getCroState(), user.getVersion());
	}
}
