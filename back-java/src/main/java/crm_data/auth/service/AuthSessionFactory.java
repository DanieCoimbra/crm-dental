package crm_data.auth.service;

import org.springframework.stereotype.Component;

import crm_data.auth.domain.User;
import crm_data.auth.service.AuthSession.UserSummary;
import crm_data.auth.service.JwtService.AccessToken;
import lombok.RequiredArgsConstructor;

/** Monta a resposta de autenticação: access token novo + refresh token em claro + resumo do usuário. */
@Component
@RequiredArgsConstructor
class AuthSessionFactory {

	private final JwtService jwtService;

	AuthSession create(User user, String refreshToken) {
		AccessToken access = jwtService.issue(user.getId(), user.getClinicId(), user.getRole());
		return new AuthSession(access.value(), refreshToken, "Bearer", access.expiresInSeconds(),
				new UserSummary(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getClinicId()));
	}
}
