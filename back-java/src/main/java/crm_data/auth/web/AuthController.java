package crm_data.auth.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import crm_data.auth.service.AuthSession;
import crm_data.auth.service.LoginService;
import crm_data.auth.service.RefreshTokenService;
import crm_data.auth.service.RegistrationService;
import crm_data.auth.web.AuthRequests.LoginRequest;
import crm_data.auth.web.AuthRequests.LogoutRequest;
import crm_data.auth.web.AuthRequests.RefreshRequest;
import crm_data.auth.web.AuthRequests.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Rotas públicas de autenticação. Só traduz DTOs; as regras ficam nos services. */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final RegistrationService registration;
	private final LoginService login;
	private final RefreshTokenService refreshTokens;

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public AuthSession register(@Valid @RequestBody RegisterRequest request) {
		return registration.register(request.clinicName(), request.name(), request.email(), request.password());
	}

	@PostMapping("/login")
	public AuthSession login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		// IP real: com forward-headers-strategy=native o Tomcat já resolve o X-Forwarded-For de proxy confiável
		return login.login(request.email(), request.password(), http.getRemoteAddr());
	}

	@PostMapping("/refresh")
	public AuthSession refresh(@Valid @RequestBody RefreshRequest request) {
		return refreshTokens.rotate(request.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody LogoutRequest request) {
		refreshTokens.revoke(request.refreshToken());
	}
}
