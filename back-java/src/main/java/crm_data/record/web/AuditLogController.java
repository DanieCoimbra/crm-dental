package crm_data.record.web;

import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crm_data.record.service.AuditQueryService;
import crm_data.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** Trilha de auditoria: somente leitura e só para o dentista. Não existe rota de escrita. */
@RestController
@RequestMapping("/api/v1/audit-log")
@Validated
@RequiredArgsConstructor
public class AuditLogController {

	private final AuditQueryService audit;

	@GetMapping
	public PageResponse<AuditEntryResponse> list(@RequestParam(required = false) String tableName,
			@RequestParam(required = false) UUID recordId,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser maior ou igual a 0") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "deve estar entre 1 e 50") @Max(value = 50, message = "deve estar entre 1 e 50") int size) {
		return PageResponse.of(audit.list(tableName, recordId, page, size), AuditEntryResponse::from);
	}
}
