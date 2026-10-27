package crm_data.scheduling.web;

import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.scheduling.service.AppointmentService;
import crm_data.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** Histórico de agendamentos de um paciente (qualquer papel), do mais recente ao mais antigo, sem prontuário. */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/appointments")
@Validated
@RequiredArgsConstructor
public class PatientAppointmentController {

	private final AppointmentService appointments;

	@GetMapping
	public PageResponse<AppointmentResponse> history(@PathVariable UUID patientId,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser maior ou igual a 0") @Max(value = 100000, message = "deve ser no máximo 100000") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "deve estar entre 1 e 50") @Max(value = 50, message = "deve estar entre 1 e 50") int size) {
		return PageResponse.of(appointments.historyOf(patientId, page, size), AppointmentResponse::from);
	}
}
