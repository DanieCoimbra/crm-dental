package crm_data.scheduling.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import crm_data.scheduling.service.AppointmentStatusService;
import crm_data.scheduling.web.AppointmentRequests.ActionRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Transições do agendamento, uma rota por ação. Recepção e dentista confirmam, cancelam e marcam falta; só o dentista
 * inicia, finaliza e reativa (rota e {@code @PreAuthorize}). Todas exigem a {@code version} lida e devolvem o
 * agendamento atualizado.
 */
@RestController
@RequestMapping("/api/v1/appointments/{id}")
@RequiredArgsConstructor
public class AppointmentStatusController {

	private final AppointmentStatusService status;

	@PostMapping("/confirm")
	public AppointmentResponse confirm(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
		return AppointmentResponse.from(status.confirm(id, request.version()));
	}

	@PostMapping("/cancel")
	public AppointmentResponse cancel(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
		return AppointmentResponse.from(status.cancel(id, request.version()));
	}

	@PostMapping("/no-show")
	public AppointmentResponse noShow(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
		return AppointmentResponse.from(status.noShow(id, request.version()));
	}

	@PostMapping("/start")
	public AppointmentResponse start(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
		return AppointmentResponse.from(status.start(id, request.version()));
	}

	@PostMapping("/finish")
	public AppointmentResponse finish(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
		return AppointmentResponse.from(status.finish(id, request.version()));
	}

	@PostMapping("/reactivate")
	public AppointmentResponse reactivate(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
		return AppointmentResponse.from(status.reactivate(id, request.version()));
	}
}
