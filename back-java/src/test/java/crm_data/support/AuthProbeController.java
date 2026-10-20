package crm_data.support;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import crm_data.shared.security.TokenPrincipal;

/** Controlador só de teste (contexto completo): rotas protegidas por token e por papel. */
@RestController
public class AuthProbeController {

	@GetMapping("/probe/auth/any")
	TokenPrincipal any() {
		return TokenPrincipal.current();
	}

	@GetMapping("/probe/auth/dentist")
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	TokenPrincipal dentistOnly() {
		return TokenPrincipal.current();
	}
}
