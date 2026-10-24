package crm_data.scheduling.web;

import java.time.LocalDate;

import crm_data.shared.web.RequestText;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corpos de {@code /clinic-holidays}. Não aceitam {@code id} nem {@code clinicId}: extras do JSON são ignorados. */
public final class HolidayRequests {

	private HolidayRequests() {
	}

	public record CreateHolidayRequest(@NotNull(message = "obrigatório") @MinHolidayDate LocalDate date,
			@NotBlank(message = "obrigatório") @Size(min = 2, max = 80, message = "deve ter entre 2 e 80 caracteres") String name) {

		public CreateHolidayRequest {
			name = RequestText.strip(name);
		}
	}

	/** @param version versão lida pelo cliente (controle de edição concorrente); obrigatória */
	public record UpdateHolidayRequest(@NotNull(message = "obrigatório") @MinHolidayDate LocalDate date,
			@NotBlank(message = "obrigatório") @Size(min = 2, max = 80, message = "deve ter entre 2 e 80 caracteres") String name,
			@NotNull(message = "obrigatório") @Min(value = 0, message = "deve ser maior ou igual a 0") Long version) {

		public UpdateHolidayRequest {
			name = RequestText.strip(name);
		}
	}
}
