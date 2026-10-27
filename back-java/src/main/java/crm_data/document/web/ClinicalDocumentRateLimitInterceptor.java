package crm_data.document.web;

import org.springframework.web.servlet.HandlerInterceptor;

import crm_data.document.service.ClinicalDocumentRateLimiter;
import crm_data.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Limite por usuário de {@code POST /api/v1/patients/{id}/clinical-documents} (emissões) e de
 * {@code GET /api/v1/clinical-documents/{id}/pdf} (PDFs). Roda antes do controller (e antes de abrir transação ou gerar
 * PDF), então o excesso não custa nada.
 */
@RequiredArgsConstructor
public class ClinicalDocumentRateLimitInterceptor implements HandlerInterceptor {

	private static final String ISSUE_PATH = "^/api/v1/patients/[^/]+/clinical-documents$";
	private static final String PDF_PATH = "^/api/v1/clinical-documents/[^/]+/pdf$";

	private final ClinicalDocumentRateLimiter limiter;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!TenantContext.isPresent()) {
			return true;
		}
		String path = request.getRequestURI();
		if ("POST".equals(request.getMethod()) && path.matches(ISSUE_PATH)) {
			limiter.checkIssue(TenantContext.userId());
		}
		else if ("GET".equals(request.getMethod()) && path.matches(PDF_PATH)) {
			limiter.checkPdf(TenantContext.userId());
		}
		return true;
	}
}
