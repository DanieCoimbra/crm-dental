package crm_data.shared.config;

import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import lombok.RequiredArgsConstructor;

/**
 * Cabeçalhos de segurança do contrato: {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY},
 * {@code Strict-Transport-Security} (só quando {@code crm.hsts-enabled}, perfil prod) e {@code Cache-Control}.
 *
 * <p>O {@code Cache-Control} padrão do Spring Security ({@code no-cache, no-store, max-age=0, must-revalidate})
 * é mantido em todas as respostas, o que cobre {@code no-store} em {@code /api/v1/auth/**} e evita cache de
 * dados clínicos.
 */
@Component
@RequiredArgsConstructor
public class SecurityHeadersConfig {

	private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

	private final CrmProperties properties;

	void apply(HeadersConfigurer<HttpSecurity> headers) {
		headers.contentTypeOptions(options -> { });
		headers.frameOptions(frame -> frame.deny());
		headers.cacheControl(cache -> { });
		if (properties.hstsEnabled()) {
			headers.httpStrictTransportSecurity(
					hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS));
		}
		else {
			headers.httpStrictTransportSecurity(hsts -> hsts.disable());
		}
	}
}
