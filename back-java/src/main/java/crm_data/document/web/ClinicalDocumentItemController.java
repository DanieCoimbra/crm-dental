package crm_data.document.web;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import crm_data.document.service.ClinicalDocumentService;
import crm_data.document.service.DocumentPdfService;
import crm_data.document.service.DocumentPdfService.PdfFile;
import crm_data.document.web.DocumentRequests.VoidDocumentRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Leitura e anulação de um documento clínico, só para o dentista. Não há edição nem exclusão. */
@RestController
@RequestMapping("/api/v1/clinical-documents")
@RequiredArgsConstructor
public class ClinicalDocumentItemController {

	private final ClinicalDocumentService documents;
	private final DocumentPdfService pdfs;

	@GetMapping("/{id}")
	public DocumentResponse get(@PathVariable UUID id) {
		return DocumentResponse.from(documents.get(id));
	}

	/**
	 * PDF inline gerado na hora (sem Storage). Cada entrega grava um {@code DOWNLOAD}; a transação do service só confirma
	 * depois do PDF pronto, então uma falha não deixa registro.
	 */
	@GetMapping("/{id}/pdf")
	public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
		PdfFile file = pdfs.pdf(id);
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.filename() + "\"")
				.header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes());
	}

	@PostMapping("/{id}/void")
	public DocumentResponse voidDocument(@PathVariable UUID id, @Valid @RequestBody VoidDocumentRequest request) {
		return DocumentResponse.from(documents.voidDocument(id, request.version(), request.reason()));
	}
}
