package crm_data.auth.web;

import crm_data.shared.web.RequestText;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpos das rotas de autenticação (contrato da F04, §2). Nenhum aceita {@code clinicId}, {@code role} ou
 * {@code id}: campos extras do JSON são ignorados. Textos e e-mail são normalizados (trim; e-mail em minúsculas)
 * já na construção, antes da validação e de qualquer busca ou gravação.
 */
public final class AuthRequests {

	public static final String LETTER_AND_DIGIT = "^(?=.*\\p{L})(?=.*\\d).+$";
	public static final String REFRESH_TOKEN = "^[A-Za-z0-9_-]{43}$";

	private AuthRequests() {
	}

	public record RegisterRequest(
			@NotBlank(message = "obrigatório") @Size(min = 2, max = 150, message = "deve ter entre 2 e 150 caracteres") String clinicName,
			@NotBlank(message = "obrigatório") @Size(min = 2, max = 120, message = "deve ter entre 2 e 120 caracteres") String name,
			@NotBlank(message = "obrigatório") @Email(message = "e-mail inválido") @Size(max = 254, message = "deve ter no máximo 254 caracteres") String email,
			@NotNull(message = "obrigatório") @Size(min = 10, max = 64, message = "deve ter entre 10 e 64 caracteres")
			@Pattern(regexp = LETTER_AND_DIGIT, message = "deve conter ao menos uma letra e um número")
			@MaxUtf8Bytes(value = 72, message = "deve ter no máximo 72 bytes") String password) {

		public RegisterRequest {
			clinicName = RequestText.strip(clinicName);
			name = RequestText.strip(name);
			email = RequestText.normalizeEmail(email);
		}

		/** Nunca imprime a senha. */
		@Override
		public String toString() {
			return "RegisterRequest[clinicName=" + clinicName + ", name=" + name + "]";
		}
	}

	public record LoginRequest(
			@NotBlank(message = "obrigatório") @Email(message = "e-mail inválido") @Size(max = 254, message = "deve ter no máximo 254 caracteres") String email,
			@NotBlank(message = "obrigatório") @Size(max = 256, message = "deve ter no máximo 256 caracteres") String password) {

		public LoginRequest {
			email = RequestText.normalizeEmail(email);
		}

		/** Nunca imprime a senha nem o e-mail completo. */
		@Override
		public String toString() {
			return "LoginRequest[]";
		}
	}

	public record RefreshRequest(
			@NotBlank(message = "obrigatório") @Pattern(regexp = REFRESH_TOKEN, message = "formato inválido") String refreshToken) {

		/** Nunca imprime o refresh token. */
		@Override
		public String toString() {
			return "RefreshRequest[]";
		}
	}

	public record LogoutRequest(
			@NotBlank(message = "obrigatório") @Pattern(regexp = REFRESH_TOKEN, message = "formato inválido") String refreshToken) {

		/** Nunca imprime o refresh token. */
		@Override
		public String toString() {
			return "LogoutRequest[]";
		}
	}
}
