package crm_data.shared.config;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.AccessTokenRevocations;
import crm_data.shared.tenant.TenantContextFilter;
import crm_data.shared.web.ClinicalCacheHeaders;
import jakarta.servlet.DispatcherType;

/**
 * Cadeia stateless da plataforma: rotas públicas (health e auth), todo o resto exige um access token JWT válido
 * (HS256, decoder de {@link JwtConfig}), CORS, cabeçalhos e 401/403 em {@code ProblemDetail}. O papel do token vira
 * autoridade para a autorização por rota e por {@code @PreAuthorize}.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	@Bean
	AccessTokenRevocations accessTokenRevocations(ObjectProvider<Clock> clock) {
		return new AccessTokenRevocations(clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource,
			ProblemResponseWriter problems, SecurityHeadersConfig headersConfig,
			AccessTokenRevocations revocations) throws Exception {
		AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			problems.write(request, response, ProblemTypes.UNAUTHORIZED, "Autenticação necessária.");
		};
		AccessDeniedHandler forbidden = (request, response, ex) ->
			problems.write(request, response, ProblemTypes.FORBIDDEN, "Você não tem permissão para esta operação.");
		http
			.csrf(AbstractHttpConfigurer::disable) // stateless: sem cookie de sessão
			.cors(AbstractHttpConfigurer::disable) // substituído pelo filtro abaixo, que responde em problem+json
			.addFilterAfter(CorsConfig.corsFilter(corsSource, problems), HeaderWriterFilter.class)
			.addFilterAfter(new ClinicalCacheHeaders(), HeaderWriterFilter.class)
			// depois da autenticação (JWT, F04) e antes da autorização: popula o tenant da requisição
			.addFilterBefore(new TenantContextFilter(problems, revocations), AuthorizationFilter.class)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache(cache -> cache.disable())
			.headers(headersConfig::apply)
			.authorizeHttpRequests(auth -> auth
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers("/actuator/health/**").permitAll()
				.requestMatchers("/api/v1/auth/**").permitAll()
				// regras de papel por rota (segunda barreira: @PreAuthorize nos services)
				.requestMatchers("/api/v1/users/**").hasRole("DENTIST_ADMIN")
				.requestMatchers(HttpMethod.PUT, "/api/v1/clinic").hasRole("DENTIST_ADMIN")
				// agenda: leitura para os dois papéis; expediente e feriados da clínica só o dentista configura
				.requestMatchers(HttpMethod.PUT, "/api/v1/business-hours").hasRole("DENTIST_ADMIN")
				.requestMatchers("/api/v1/clinic-holidays", "/api/v1/clinic-holidays/**").hasRole("DENTIST_ADMIN")
				// a recepção confirma, cancela e marca falta; só o dentista inicia, finaliza e reativa
				.requestMatchers(HttpMethod.POST, "/api/v1/appointments/*/start", "/api/v1/appointments/*/finish",
						"/api/v1/appointments/*/reactivate").hasRole("DENTIST_ADMIN")
				.requestMatchers(HttpMethod.PATCH, "/api/v1/patients/*/active").hasRole("DENTIST_ADMIN")
				.requestMatchers(HttpMethod.POST, "/api/v1/patients/*/anonymize").hasRole("DENTIST_ADMIN")
				.requestMatchers("/api/v1/audit-log/**").hasRole("DENTIST_ADMIN")
				.requestMatchers("/api/v1/patients/*/medical-history", "/api/v1/patients/*/notes/**").hasRole("DENTIST_ADMIN")
				// exames: a recepção só anexa (POST cai em authenticated); lista, URL assinada e remoção são do dentista
				.requestMatchers(HttpMethod.GET, "/api/v1/patients/*/files/**").hasRole("DENTIST_ADMIN")
				.requestMatchers(HttpMethod.DELETE, "/api/v1/patients/*/files/**").hasRole("DENTIST_ADMIN")
				// comercial e financeiro (Fase 3): a recepção só lê procedimentos, orçamentos e PDFs; escrita e todo o
				// financeiro (parcelas, pagamentos, relatórios, ficha) são do dentista. Mais específico antes do geral.
				.requestMatchers(HttpMethod.POST, "/api/v1/procedure-types", "/api/v1/procedure-types/**")
					.hasRole("DENTIST_ADMIN")
				.requestMatchers(HttpMethod.PUT, "/api/v1/procedure-types/**").hasRole("DENTIST_ADMIN")
				.requestMatchers(HttpMethod.PATCH, "/api/v1/procedure-types/**").hasRole("DENTIST_ADMIN")
				.requestMatchers(HttpMethod.POST, "/api/v1/budgets", "/api/v1/budgets/**").hasRole("DENTIST_ADMIN")
				.requestMatchers(HttpMethod.PUT, "/api/v1/budgets/**").hasRole("DENTIST_ADMIN")
				.requestMatchers(HttpMethod.GET, "/api/v1/budgets/*/installments").hasRole("DENTIST_ADMIN")
				.requestMatchers("/api/v1/installments/**", "/api/v1/payments/**", "/api/v1/finance/**",
						"/api/v1/patients/*/financial").hasRole("DENTIST_ADMIN")
				.anyRequest().authenticated())
			.oauth2ResourceServer(resourceServer -> resourceServer
				.bearerTokenResolver(bearerTokenResolver())
				.authenticationEntryPoint(unauthorized)
				.accessDeniedHandler(forbidden)
				.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(unauthorized)
				.accessDeniedHandler(forbidden));
		return http.build();
	}

	/**
	 * Lê o Bearer só do cabeçalho Authorization e ignora rotas públicas: um access token vencido guardado no
	 * navegador não pode derrubar login, registro, refresh nem o health check.
	 */
	private static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
		return request -> {
			String path = request.getRequestURI();
			if (path.startsWith("/api/v1/auth/") || path.startsWith("/actuator/health")) {
				return null;
			}
			return delegate.resolve(request);
		};
	}

	/** O papel do token vira a autoridade {@code ROLE_<role>} (usada por {@code hasRole} e {@code @PreAuthorize}). */
	private static JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("role");
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}
}
