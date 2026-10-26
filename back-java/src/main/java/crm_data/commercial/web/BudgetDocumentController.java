package crm_data.commercial.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import crm_data.commercial.service.BudgetDocumentService;
import crm_data.commercial.service.BudgetDocumentService.DownloadUrl;
import crm_data.commercial.service.BudgetService.DocumentSummary;
import crm_data.commercial.web.BudgetRequests.ActionRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Envio do orçamento e PDF. Só o dentista envia e reemite (rota e {@code @PreAuthorize}); os dois papéis listam as
 * revisões e baixam. O envio e a reemissão têm limite por usuário (429) e por instância (503).
 */
@RestController
@RequestMapping("/api/v1/budgets/{budgetId}")
@RequiredArgsConstructor
public class BudgetDocumentController {

	/** Validade da URL de download informada ao cliente vem do serviço; aqui só o formato. */
	public record DownloadUrlResponse(String url, long expiresInSeconds) {

		static DownloadUrlResponse from(DownloadUrl download) {
			return new DownloadUrlResponse(download.url().toString(), download.expiresInSeconds());
		}
	}

	private final BudgetDocumentService documents;

	/** {@code DRAFT → SENT} e PDF da revisão 1, na mesma transação. */
	@PostMapping("/send")
	public BudgetResponse send(@PathVariable UUID budgetId, @Valid @RequestBody ActionRequest request) {
		return BudgetResponse.from(documents.send(budgetId, request.version()));
	}

	/** Nova revisão do PDF, sem mudar o status. */
	@PostMapping("/documents")
	public ResponseEntity<DocumentResponse> regenerate(@PathVariable UUID budgetId) {
		DocumentSummary created = documents.regenerate(budgetId);
		return ResponseEntity.created(URI.create("/api/v1/budgets/" + budgetId + "/documents/" + created.id() + "/download-url"))
				.body(DocumentResponse.from(created));
	}

	@GetMapping("/documents")
	public List<DocumentResponse> list(@PathVariable UUID budgetId) {
		return documents.list(budgetId).stream().map(DocumentResponse::from).toList();
	}

	@GetMapping("/documents/{documentId}/download-url")
	public DownloadUrlResponse downloadUrl(@PathVariable UUID budgetId, @PathVariable UUID documentId) {
		return DownloadUrlResponse.from(documents.downloadUrl(budgetId, documentId));
	}
}
