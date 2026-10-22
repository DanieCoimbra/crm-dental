package crm_data.record.web;

import org.springframework.web.servlet.HandlerInterceptor;

import crm_data.record.service.UploadRateLimiter;
import crm_data.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Limite de 10 uploads por minuto por usuário em {@code POST /api/v1/patients/{id}/files}. Roda antes do controller;
 * com {@code spring.servlet.multipart.resolve-lazily=true} o corpo multipart ainda não foi lido, então requisições
 * acima do limite (ou malformadas) não custam o parse do arquivo.
 */
@RequiredArgsConstructor
public class UploadRateLimitInterceptor implements HandlerInterceptor {

	private static final String UPLOAD_PATH = "^/api/v1/patients/[^/]+/files$";

	private final UploadRateLimiter limiter;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if ("POST".equals(request.getMethod()) && request.getRequestURI().matches(UPLOAD_PATH)
				&& TenantContext.isPresent()) {
			limiter.check(TenantContext.userId());
		}
		return true;
	}
}
