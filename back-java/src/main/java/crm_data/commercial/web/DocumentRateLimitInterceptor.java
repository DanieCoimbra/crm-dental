package crm_data.commercial.web;

import org.springframework.web.servlet.HandlerInterceptor;

import crm_data.commercial.service.DocumentRateLimiter;
import crm_data.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Limite de envios e reemissões de PDF por minuto por usuário em {@code POST /api/v1/budgets/{id}/send} e
 * {@code POST /api/v1/budgets/{id}/documents}. Roda antes do controller (e antes de abrir transação ou gerar PDF), então o
 * excesso não custa nada.
 */
@RequiredArgsConstructor
public class DocumentRateLimitInterceptor implements HandlerInterceptor {

	private static final String PATH = "^/api/v1/budgets/[^/]+/(send|documents)$";

	private final DocumentRateLimiter limiter;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if ("POST".equals(request.getMethod()) && request.getRequestURI().matches(PATH) && TenantContext.isPresent()) {
			limiter.check(TenantContext.userId());
		}
		return true;
	}
}
