package crm_data.auth.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import crm_data.auth.service.LoginRateLimiter;

/** Registra o limitador por IP nas rotas de autenticação (opcional: fatias de teste web sem o módulo não o têm). */
@Configuration(proxyBeanMethods = false)
public class AuthWebConfig implements WebMvcConfigurer {

	private final ObjectProvider<LoginRateLimiter> rateLimiter;

	public AuthWebConfig(ObjectProvider<LoginRateLimiter> rateLimiter) {
		this.rateLimiter = rateLimiter;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		rateLimiter.ifAvailable(limiter -> registry.addInterceptor(new AuthRateLimitInterceptor(limiter))
				.addPathPatterns("/api/v1/auth/**"));
	}
}
