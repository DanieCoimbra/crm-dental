package crm_data.clinic.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import crm_data.auth.web.UserResponse;
import crm_data.clinic.service.UserAdminService;
import crm_data.clinic.web.UserRequests.CreateUserRequest;
import crm_data.clinic.web.UserRequests.ResetPasswordRequest;
import crm_data.clinic.web.UserRequests.SetActiveRequest;
import crm_data.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** Gestão da equipe. Todas as rotas são exclusivas do dentista (regra de rota + {@code @PreAuthorize} no service). */
@RestController
@RequestMapping("/api/v1/users")
@Validated
@RequiredArgsConstructor
public class UserController {

	private final UserAdminService users;

	@GetMapping
	public PageResponse<UserResponse> list(
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser maior ou igual a 0") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "deve estar entre 1 e 50") @Max(value = 50, message = "deve estar entre 1 e 50") int size) {
		return PageResponse.of(users.list(page, size), UserResponse::from);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
		return UserResponse.from(users.createReceptionist(request.name(), request.email(), request.password()));
	}

	@PatchMapping("/{id}/active")
	public UserResponse setActive(@PathVariable UUID id, @Valid @RequestBody SetActiveRequest request) {
		return UserResponse.from(users.setActive(id, request.active()));
	}

	@PutMapping("/{id}/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
		users.resetPassword(id, request.newPassword());
	}
}
