package crm_data.record.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import crm_data.record.service.ClinicalNoteService;
import crm_data.record.web.RecordRequests.CreateNoteRequest;
import crm_data.record.web.RecordRequests.UpdateNoteRequest;
import crm_data.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Notas de atendimento do paciente, só para o dentista. Não há exclusão. Atenção: toda gravação reinicia a contagem
 * de 20 anos de retenção legal do prontuário.
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/notes")
@Validated
@RequiredArgsConstructor
public class ClinicalNoteController {

	private final ClinicalNoteService notes;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public NoteResponse create(@PathVariable UUID patientId, @Valid @RequestBody CreateNoteRequest request) {
		return NoteResponse.from(notes.create(patientId, request.content(), request.notedAt()));
	}

	@GetMapping
	public PageResponse<NoteResponse> list(@PathVariable UUID patientId,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser maior ou igual a 0") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "deve estar entre 1 e 50") @Max(value = 50, message = "deve estar entre 1 e 50") int size) {
		return PageResponse.of(notes.list(patientId, page, size), NoteResponse::from);
	}

	@PutMapping("/{noteId}")
	public NoteResponse update(@PathVariable UUID patientId, @PathVariable UUID noteId,
			@Valid @RequestBody UpdateNoteRequest request) {
		return NoteResponse.from(notes.update(patientId, noteId, request.content(), request.notedAt(), request.version()));
	}
}
