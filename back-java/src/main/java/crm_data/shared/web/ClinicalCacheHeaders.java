package crm_data.shared.web;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Marca com {@code Cache-Control: no-store} as respostas das rotas clínicas ({@code /api/v1/patients/**} e
 * {@code /api/v1/audit-log/**}), que carregam dado de saúde e URLs assinadas, e das rotas da agenda
 * ({@code /appointments}, {@code /business-hours}, {@code /holidays}, {@code /clinic-holidays}), que revelam quem é
 * paciente da clínica, e as rotas comerciais e financeiras da Fase 3 ({@code /procedure-types}, {@code /budgets},
 * {@code /installments}, {@code /payments}, {@code /finance}), que carregam valores e PDFs. O cabeçalho é definido antes da
 * cadeia; o escritor padrão do Spring Security não sobrescreve um {@code Cache-Control} já presente.
 */
public class ClinicalCacheHeaders extends OncePerRequestFilter {

	private static final List<String> PREFIXES = List.of("/api/v1/patients", "/api/v1/audit-log", "/api/v1/appointments",
			"/api/v1/business-hours", "/api/v1/holidays", "/api/v1/clinic-holidays", "/api/v1/procedure-types",
			"/api/v1/budgets", "/api/v1/installments", "/api/v1/payments", "/api/v1/finance", "/api/v1/clinical-documents",
			"/api/v1/dashboard");

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return PREFIXES.stream().noneMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		chain.doFilter(request, response);
	}
}
