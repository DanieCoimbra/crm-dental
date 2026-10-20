package crm_data.shared.config;

import java.io.IOException;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import crm_data.shared.error.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * CORS restrito às origens de {@code CRM_CORS_ORIGINS}. Origem fora da lista não recebe cabeçalhos CORS
 * e a requisição é recusada com 403 {@code forbidden} em {@code application/problem+json}.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

	static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
	static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Content-Type", "X-Request-Id");
	static final List<String> EXPOSED_HEADERS = List.of("X-Request-Id", "Retry-After");

	@Bean
	CorsConfigurationSource corsConfigurationSource(CrmProperties properties) {
		List<String> origins = properties.corsOrigins();
		if (origins.contains("*")) {
			throw new IllegalStateException("CRM_CORS_ORIGINS não pode conter '*': liste as origens explicitamente.");
		}
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(origins);
		configuration.setAllowedMethods(ALLOWED_METHODS);
		configuration.setAllowedHeaders(ALLOWED_HEADERS);
		configuration.setExposedHeaders(EXPOSED_HEADERS);
		configuration.setAllowCredentials(false); // tokens vão no cabeçalho Authorization, não em cookies
		configuration.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	/** Filtro montado manualmente (não é um bean {@code Filter}) e encaixado na cadeia do Spring Security. */
	static CorsFilter corsFilter(CorsConfigurationSource source, ProblemResponseWriter writer) {
		CorsFilter filter = new CorsFilter(source);
		filter.setCorsProcessor(new ProblemCorsProcessor(writer));
		return filter;
	}

	static final class ProblemCorsProcessor extends DefaultCorsProcessor {

		private final ProblemResponseWriter writer;

		ProblemCorsProcessor(ProblemResponseWriter writer) {
			this.writer = writer;
		}

		@Override
		public boolean processRequest(CorsConfiguration config, HttpServletRequest request, HttpServletResponse response)
				throws IOException {
			if (config != null && CorsUtils.isCorsRequest(request)
					&& config.checkOrigin(request.getHeader(HttpHeaders.ORIGIN)) == null) {
				writer.write(request, response, ProblemTypes.FORBIDDEN, "Origem não permitida.");
				return false;
			}
			return super.processRequest(config, request, response);
		}
	}
}
