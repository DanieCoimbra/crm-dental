package crm_data.scheduling.web;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.service.AppointmentService;
import crm_data.scheduling.service.AppointmentService.AppointmentView;
import crm_data.scheduling.web.AppointmentRequests.CreateAppointmentRequest;
import crm_data.scheduling.web.AppointmentRequests.RescheduleRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Agenda: recepção e dentista criam, consultam e remarcam. As transições de status são da F04. */
@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
public class AppointmentController {

	private final AppointmentService appointments;

	@PostMapping
	public ResponseEntity<AppointmentResponse> create(@Valid @RequestBody CreateAppointmentRequest request) {
		AppointmentView created = appointments.create(request.patientId(), request.startsAt(), request.endsAt(),
				request.normalizedNotes(), request.fits());
		return ResponseEntity.created(URI.create("/api/v1/appointments/" + created.appointment().getId()))
				.body(AppointmentResponse.from(created));
	}

	/** {@code from} e {@code to} são datas locais da clínica ({@code to} inclusivo); janela de até 42 dias. */
	@GetMapping
	public List<AppointmentResponse> agenda(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) List<AppointmentStatus> status) {
		return AppointmentResponse.from(appointments.list(from, to, status));
	}

	@GetMapping("/{id}")
	public AppointmentResponse get(@PathVariable UUID id) {
		return AppointmentResponse.from(appointments.get(id));
	}

	@PutMapping("/{id}")
	public AppointmentResponse reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest request) {
		return AppointmentResponse.from(appointments.reschedule(id, request.startsAt(), request.endsAt(),
				request.normalizedNotes(), request.fits(), request.version()));
	}
}
