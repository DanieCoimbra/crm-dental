package crm_data.auth.web;

import crm_data.shared.web.RequestText;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpos de {@code PUT /me} e {@code PUT /me/password}. Nenhum aceita {@code clinicId}, {@code role} ou {@code id}:
 * campos extras do JSON são ignorados.
 */
public final class ProfileRequests {

	private ProfileRequests() {
	}

	public record UpdateProfileRequest(
			@NotBlank(message = "obrigatório") @Size(min = 2, max = 120, message = "deve ter entre 2 e 120 caracteres") String name,
			@NotBlank(message = "obrigatório") @Email(message = "e-mail inválido") @Size(max = 254, message = "deve ter no máximo 254 caracteres") String email) {

		public UpdateProfileRequest {
			name = RequestText.strip(name);
			email = RequestText.normalizeEmail(email);
		}
	}

	public record ChangePasswordRequest(
			@NotBlank(message = "obrigatório") @Size(max = 256, message = "deve ter no máximo 256 caracteres") String currentPassword,
			@NotNull(message = "obrigatório") @Size(min = 10, max = 64, message = "deve ter entre 10 e 64 caracteres")
			@Pattern(regexp = AuthRequests.LETTER_AND_DIGIT, message = "deve conter ao menos uma letra e um número")
			@MaxUtf8Bytes(value = 72, message = "deve ter no máximo 72 bytes") String newPassword) {

		/** Nunca imprime as senhas. */
		@Override
		public String toString() {
			return "ChangePasswordRequest[]";
		}
	}
}
