package crm_data.catalog.web;

import java.math.BigDecimal;

import crm_data.shared.money.Money;
import crm_data.shared.web.RequestText;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpos de {@code /procedure-types}. Não aceitam {@code id}, {@code clinicId}, {@code createdBy} nem {@code active}
 * na criação e na edição: extras do JSON são ignorados. O preço é texto decimal ({@code "250.00"}), nunca número JSON.
 */
public final class ProcedureTypeRequests {

	static final String PRICE_MESSAGE = "valor monetário inválido (texto decimal de 0 a 999999.99, ex.: \"250.00\")";
	static final String COLOR_PATTERN = "^#[0-9A-Fa-f]{6}$";

	private ProcedureTypeRequests() {
	}

	public record CreateProcedureTypeRequest(
			@NotBlank(message = "obrigatório") @Size(min = 2, max = 80, message = "deve ter entre 2 e 80 caracteres") String name,
			@NotNull(message = "obrigatório") @Pattern(regexp = Money.INPUT_PATTERN, message = PRICE_MESSAGE) String basePrice,
			@NotNull(message = "obrigatório") @Min(value = 5, message = "deve estar entre 5 e 720") @Max(value = 720, message = "deve estar entre 5 e 720") @DurationStep Integer defaultDurationMinutes,
			@NotNull(message = "obrigatório") @Pattern(regexp = COLOR_PATTERN, message = "use o formato #RRGGBB") String color) {

		public CreateProcedureTypeRequest {
			name = RequestText.strip(name);
			color = RequestText.strip(color);
		}

		public BigDecimal price() {
			return Money.parse(basePrice);
		}

		public short minutes() {
			return defaultDurationMinutes.shortValue();
		}
	}

	/** @param version versão lida pelo cliente (controle de edição concorrente); obrigatória */
	public record UpdateProcedureTypeRequest(
			@NotBlank(message = "obrigatório") @Size(min = 2, max = 80, message = "deve ter entre 2 e 80 caracteres") String name,
			@NotNull(message = "obrigatório") @Pattern(regexp = Money.INPUT_PATTERN, message = PRICE_MESSAGE) String basePrice,
			@NotNull(message = "obrigatório") @Min(value = 5, message = "deve estar entre 5 e 720") @Max(value = 720, message = "deve estar entre 5 e 720") @DurationStep Integer defaultDurationMinutes,
			@NotNull(message = "obrigatório") @Pattern(regexp = COLOR_PATTERN, message = "use o formato #RRGGBB") String color,
			@NotNull(message = "obrigatório") @Min(value = 0, message = "deve ser maior ou igual a 0") Long version) {

		public UpdateProcedureTypeRequest {
			name = RequestText.strip(name);
			color = RequestText.strip(color);
		}

		public BigDecimal price() {
			return Money.parse(basePrice);
		}

		public short minutes() {
			return defaultDurationMinutes.shortValue();
		}
	}

	public record SetActiveRequest(@NotNull(message = "obrigatório") Boolean active,
			@NotNull(message = "obrigatório") @Min(value = 0, message = "deve ser maior ou igual a 0") Long version) {
	}
}
