package crm_data.scheduling.web;

import java.time.Instant;
import java.util.UUID;

import crm_data.shared.web.RequestText;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Corpos de {@code /appointments}. Não aceitam {@code id}, {@code clinicId}, {@code createdBy} nem {@code status}: campos
 * extras do JSON são ignorados. {@code patientId} só existe na criação. {@code procedureTypeId} (Fase 3) é opcional; no
 * {@code PUT} <strong>substitui</strong> o valor atual (ausente ou {@code null} remove o vínculo).
 */
public final class AppointmentRequests {

	private AppointmentRequests() {
	}

	/**
	 * @param notes observação em texto claro, até 500 caracteres; <strong>não leva dado clínico</strong>
	 * @param allowOutsideHours encaixe fora do expediente/feriado; só o dentista pode (a recepção recebe 403)
	 */
	@ValidAppointmentWindow
	public record CreateAppointmentRequest(@NotNull(message = "obrigatório") UUID patientId,
			@NotNull(message = "obrigatório") Instant startsAt, @NotNull(message = "obrigatório") Instant endsAt, String notes,
			Boolean allowOutsideHours, UUID procedureTypeId) implements ValidAppointmentWindow.Window {

		public String normalizedNotes() {
			return RequestText.blankToNull(notes);
		}

		public boolean fits() {
			return Boolean.TRUE.equals(allowOutsideHours);
		}
	}

	/** Corpo das ações de status: só a versão lida pelo cliente (controle de edição concorrente). */
	public record ActionRequest(@NotNull(message = "obrigatório") @Min(value = 0, message = "deve ser maior ou igual a 0") Long version) {
	}

	/**
	 * Remarcação e edição da observação: {@code startsAt} e {@code endsAt} são obrigatórios e {@code notes} substitui o
	 * valor atual ({@code null} limpa). {@code patientId} não existe aqui: o paciente de um agendamento não muda.
	 *
	 * @param version versão lida pelo cliente (controle de edição concorrente); obrigatória
	 */
	@ValidAppointmentWindow
	public record RescheduleRequest(@NotNull(message = "obrigatório") Instant startsAt,
			@NotNull(message = "obrigatório") Instant endsAt, String notes, Boolean allowOutsideHours, UUID procedureTypeId,
			@NotNull(message = "obrigatório") @Min(value = 0, message = "deve ser maior ou igual a 0") Long version)
			implements ValidAppointmentWindow.Window {

		public String normalizedNotes() {
			return RequestText.blankToNull(notes);
		}

		public boolean fits() {
			return Boolean.TRUE.equals(allowOutsideHours);
		}
	}
}
