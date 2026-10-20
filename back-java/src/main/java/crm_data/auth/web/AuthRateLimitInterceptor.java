package crm_data.auth.web;

import org.springframework.web.servlet.HandlerInterceptor;

import crm_data.auth.service.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Limite por IP (20/min) em registro, login e refresh. Roda antes da validação do corpo, para que requisições
 * malformadas em massa também contem. O limite por IP+e-mail do login fica no {@code LoginService}.
 */
@RequiredArgsConstructor
public class AuthRateLimitInterceptor implements HandlerInterceptor {

	private final LoginRateLimiter rateLimiter;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if ("POST".equals(request.getMethod())) {
			String path = request.getRequestURI();
			for (String action : new String[] { "register", "login", "refresh" }) {
				if (path.equals("/api/v1/auth/" + action)) {
					rateLimiter.checkIp(action, request.getRemoteAddr());
					break;
				}
			}
		}
		return true;
	}
}
