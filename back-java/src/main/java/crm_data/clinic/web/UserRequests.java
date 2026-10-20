package crm_data.clinic.web;

import crm_data.auth.web.AuthRequests;
import crm_data.auth.web.MaxUtf8Bytes;
import crm_data.shared.web.RequestText;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpos das rotas {@code /users}. Nenhum aceita {@code clinicId}, {@code role} ou {@code id}: o papel do usuário
 * criado é sempre {@code RECEPTIONIST} e a clínica vem do token. Extras do JSON são ignorados.
 */
public final class UserRequests {

	private UserRequests() {
	}

	public record CreateUserRequest(
			@NotBlank(message = "obrigatório") @Size(min = 2, max = 120, message = "deve ter entre 2 e 120 caracteres") String name,
			@NotBlank(message = "obrigatório") @Email(message = "e-mail inválido") @Size(max = 254, message = "deve ter no máximo 254 caracteres") String email,
			@NotNull(message = "obrigatório") @Size(min = 10, max = 64, message = "deve ter entre 10 e 64 caracteres")
			@Pattern(regexp = AuthRequests.LETTER_AND_DIGIT, message = "deve conter ao menos uma letra e um número")
			@MaxUtf8Bytes(value = 72, message = "deve ter no máximo 72 bytes") String password) {

		public CreateUserRequest {
			name = RequestText.strip(name);
			email = RequestText.normalizeEmail(email);
		}

		/** Nunca imprime a senha. */
		@Override
		public String toString() {
			return "CreateUserRequest[name=" + name + "]";
		}
	}

	public record SetActiveRequest(@NotNull(message = "obrigatório") Boolean active) {
	}

	public record ResetPasswordRequest(
			@NotNull(message = "obrigatório") @Size(min = 10, max = 64, message = "deve ter entre 10 e 64 caracteres")
			@Pattern(regexp = AuthRequests.LETTER_AND_DIGIT, message = "deve conter ao menos uma letra e um número")
			@MaxUtf8Bytes(value = 72, message = "deve ter no máximo 72 bytes") String newPassword) {

		/** Nunca imprime a senha. */
		@Override
		public String toString() {
			return "ResetPasswordRequest[]";
		}
	}
}
