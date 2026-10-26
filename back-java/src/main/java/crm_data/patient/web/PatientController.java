package crm_data.patient.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import crm_data.patient.service.PatientAnonymizationService;
import crm_data.patient.service.PatientAnonymizationService.Result;
import crm_data.patient.service.PatientService;
import crm_data.patient.service.PatientService.Status;
import crm_data.patient.web.PatientRequests.CreatePatientRequest;
import crm_data.patient.web.PatientRequests.SetActiveRequest;
import crm_data.patient.web.PatientRequests.UpdatePatientRequest;
import crm_data.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

/** Pacientes: cadastro, busca, ficha, edição e inativação. Papéis aplicados por rota e por {@code @PreAuthorize}. */
@RestController
@RequestMapping("/api/v1/patients")
@Validated
@RequiredArgsConstructor
public class PatientController {

	private final PatientService patients;
	private final PatientAnonymizationService anonymization;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PatientResponse create(@Valid @RequestBody CreatePatientRequest request) {
		return PatientResponse.from(patients.create(request.toDetails()));
	}

	@GetMapping
	public PageResponse<PatientSummaryResponse> search(@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "active") @Pattern(regexp = "active|inactive|all", message = "deve ser active, inactive ou all") String status,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser maior ou igual a 0") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "deve estar entre 1 e 50") @Max(value = 50, message = "deve estar entre 1 e 50") int size) {
		return PageResponse.of(patients.search(q, Status.parse(status), page, size), PatientSummaryResponse::from);
	}

	@GetMapping("/{id}")
	public PatientResponse get(@PathVariable UUID id) {
		return PatientResponse.from(patients.get(id));
	}

	@PutMapping("/{id}")
	public PatientResponse update(@PathVariable UUID id, @Valid @RequestBody UpdatePatientRequest request) {
		return PatientResponse.from(patients.update(id, request.toDetails(), request.version()));
	}

	@PatchMapping("/{id}/active")
	public PatientResponse setActive(@PathVariable UUID id, @Valid @RequestBody SetActiveRequest request) {
		return PatientResponse.from(patients.setActive(id, request.active()));
	}

	/**
	 * Anonimiza o paciente (LGPD), só o dentista. Irreversível. Só vale para paciente inativo e com a retenção legal
	 * vencida. A limpeza dos objetos no Storage acontece depois do commit; se falhar, {@code storageCleanup} é
	 * {@code PENDING} e a anonimização permanece.
	 */
	@PostMapping("/{id}/anonymize")
	public AnonymizationResponse anonymize(@PathVariable UUID id) {
		Result result = anonymization.anonymize(id);
		return new AnonymizationResponse(result.anonymizedAt(), result.storageCleanup().name(), result.openBudgets());
	}
}
