package crm_data.auth.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import crm_data.auth.service.ProfileService;
import crm_data.auth.web.ProfileRequests.ChangePasswordRequest;
import crm_data.auth.web.ProfileRequests.ProfessionalRegistryRequest;
import crm_data.auth.web.ProfileRequests.UpdateProfileRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Perfil do usuário autenticado (qualquer papel). Todas as rotas operam só sobre o usuário do token. */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

	private final ProfileService profile;

	@GetMapping
	public UserResponse me() {
		return UserResponse.from(profile.me());
	}

	@PutMapping
	public UserResponse update(@Valid @RequestBody UpdateProfileRequest request) {
		return UserResponse.from(profile.updateProfile(request.name(), request.email(), request.currentPassword()));
	}

	/** Só o dentista (rota em {@code SecurityConfig}); campo omitido e {@code null} se confundem, por isso rota própria. */
	@PutMapping("/professional-registry")
	public UserResponse registerProfessional(@Valid @RequestBody ProfessionalRegistryRequest request) {
		return UserResponse.from(profile.registerProfessional(request.cro(), request.croState(), request.version()));
	}

	@PutMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
		profile.changePassword(request.currentPassword(), request.newPassword());
	}
}
