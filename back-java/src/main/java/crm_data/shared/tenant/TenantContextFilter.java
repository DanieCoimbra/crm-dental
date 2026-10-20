package crm_data.shared.tenant;

import java.io.IOException;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Depois da validação do JWT, copia {@code clinic_id}, {@code sub} e {@code role} do token para o
 * {@link TenantContext} e o limpa ao fim da requisição. Token com claims ausentes ou malformadas é recusado com 401.
 * Requisições sem JWT (rotas públicas) seguem sem contexto.
 */
@RequiredArgsConstructor
public class TenantContextFilter extends OncePerRequestFilter {

	static final String CLAIM_CLINIC_ID = "clinic_id";
	static final String CLAIM_ROLE = "role";

	private final ProblemResponseWriter problems;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		TenantContext.clear(); // nunca herdar contexto de uma requisição anterior da mesma thread
		try {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
				try {
					populate(token.getToken());
				}
				catch (IllegalArgumentException ex) {
					problems.write(request, response, ProblemTypes.UNAUTHORIZED, "Token inválido.");
					return;
				}
			}
			chain.doFilter(request, response);
		}
		finally {
			TenantContext.clear();
		}
	}

	private static void populate(Jwt jwt) {
		UUID clinicId = uuid(required(jwt.getClaimAsString(CLAIM_CLINIC_ID), CLAIM_CLINIC_ID));
		UUID userId = uuid(required(jwt.getSubject(), "sub"));
		Role role = Role.valueOf(required(jwt.getClaimAsString(CLAIM_ROLE), CLAIM_ROLE));
		TenantContext.set(clinicId, userId, role);
	}

	private static String required(String value, String claim) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("claim ausente: " + claim);
		}
		return value;
	}

	/** UUID canônico (minúsculas, com hífens); {@code UUID.fromString} sozinho aceita formas encurtadas. */
	private static UUID uuid(String value) {
		UUID parsed = UUID.fromString(value);
		if (!parsed.toString().equals(value.toLowerCase())) {
			throw new IllegalArgumentException("uuid não canônico");
		}
		return parsed;
	}
}
