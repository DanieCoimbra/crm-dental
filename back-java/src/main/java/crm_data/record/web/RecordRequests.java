package crm_data.record.web;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corpos das rotas de prontuário. Nenhum aceita {@code id}, {@code patientId}, {@code clinicId} nem
 * {@code createdBy}: extras do JSON são ignorados. O texto clínico não é aparado nem normalizado, e o
 * {@code toString} nunca o imprime. Os limites contam unidades UTF-16 e cabem nas colunas cifradas.
 */
public final class RecordRequests {

	private RecordRequests() {
	}

	/**
	 * {@code version} é obrigatória só quando o histórico já existe; a criação a ignora, qualquer que seja o valor. Na
	 * atualização, uma versão que não seja a atual (inclusive negativa) é conflito (409).
	 */
	public record SaveHistoryRequest(
			@NotBlank(message = "obrigatório") @Size(max = 10000, message = "deve ter no máximo 10000 caracteres") String content,
			Long version) {

		@Override
		public String toString() {
			return "SaveHistoryRequest[]";
		}
	}

	/** @param appointmentId atendimento em que a nota ocorreu (opcional; do mesmo paciente; no máximo uma nota por atendimento) */
	public record CreateNoteRequest(
			@NotBlank(message = "obrigatório") @Size(max = 5000, message = "deve ter no máximo 5000 caracteres") String content,
			Instant notedAt, UUID appointmentId) {

		@Override
		public String toString() {
			return "CreateNoteRequest[]";
		}
	}

	/**
	 * @param appointmentId <strong>substitui</strong> o vínculo com o atendimento: preenchido vincula ou troca; {@code null}
	 *        ou ausente desvincula. Quem quer manter o vínculo reenvia o valor atual (a resposta da nota o traz)
	 */
	public record UpdateNoteRequest(
			@NotBlank(message = "obrigatório") @Size(max = 5000, message = "deve ter no máximo 5000 caracteres") String content,
			@NotNull(message = "obrigatório") Instant notedAt,
			@NotNull(message = "obrigatório") @Min(value = 0, message = "deve ser maior ou igual a 0") Long version,
			UUID appointmentId) {

		@Override
		public String toString() {
			return "UpdateNoteRequest[]";
		}
	}
}
