package crm_data.auth.web;

import java.util.UUID;

import crm_data.auth.domain.User;
import crm_data.shared.security.Role;

/** Usuário na API (contrato da F05, §3). Nunca contém o hash da senha: ele nem existe neste tipo. */
public record UserResponse(UUID id, String name, String email, Role role, boolean active, UUID clinicId) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.isActive(),
				user.getClinicId());
	}
}
