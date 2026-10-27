package crm_data.document.web;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.document.domain.DocumentType;
import crm_data.document.service.ClinicalDocumentService;
import crm_data.document.service.DocumentView;
import crm_data.document.web.DocumentRequests.CreateDocumentRequest;
import crm_data.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** Emissão e histórico de documentos clínicos do paciente, só para o dentista. */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/clinical-documents")
@Validated
@RequiredArgsConstructor
public class ClinicalDocumentController {

	private final ClinicalDocumentService documents;

	@PostMapping
	public ResponseEntity<DocumentResponse> create(@PathVariable UUID patientId, @Valid @RequestBody CreateDocumentRequest request) {
		DocumentView view = documents.create(patientId, request.documentType(), request.issuedOn(), request.restDays(),
				request.targetSpecialty(), request.content(), request.appointmentId());
		return ResponseEntity.created(URI.create("/api/v1/clinical-documents/" + view.document().getId()))
				.body(DocumentResponse.from(view));
	}

	@GetMapping
	public PageResponse<DocumentSummaryResponse> list(@PathVariable UUID patientId,
			@RequestParam(required = false) DocumentType type,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser maior ou igual a 0") @Max(value = 100000, message = "deve ser no máximo 100000") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "deve estar entre 1 e 50") @Max(value = 50, message = "deve estar entre 1 e 50") int size) {
		return PageResponse.of(documents.list(patientId, type, page, size), DocumentSummaryResponse::from);
	}
}
