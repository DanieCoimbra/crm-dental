package crm_data.clinic.web;

import crm_data.shared.web.RequestText;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corpo de {@code PUT /clinic}. Não aceita {@code id} nem {@code clinicId}: extras do JSON são ignorados. */
public final class ClinicRequests {

	private ClinicRequests() {
	}

	/**
	 * @param version versão lida pelo cliente (controle de edição concorrente); obrigatória
	 */
	public record UpdateClinicRequest(
			@NotBlank(message = "obrigatório") @Size(min = 2, max = 150, message = "deve ter entre 2 e 150 caracteres") String name,
			@Size(max = 20, message = "deve ter no máximo 20 caracteres") String document,
			@Size(max = 20, message = "deve ter no máximo 20 caracteres") String phone,
			@Email(message = "e-mail inválido") @Size(max = 254, message = "deve ter no máximo 254 caracteres") String email,
			@Size(max = 255, message = "deve ter no máximo 255 caracteres") String address,
			@Size(max = 50, message = "deve ter no máximo 50 caracteres") @ValidTimeZone String timezone,
			@NotNull(message = "obrigatório") @Min(value = 0, message = "deve ser maior ou igual a 0") Long version) {

		public UpdateClinicRequest {
			name = RequestText.strip(name);
			document = RequestText.blankToNull(document);
			phone = RequestText.blankToNull(phone);
			email = RequestText.blankToNull(email);
			address = RequestText.blankToNull(address);
			timezone = RequestText.blankToNull(timezone);
			if (email != null) {
				email = RequestText.normalizeEmail(email);
			}
		}
	}
}
