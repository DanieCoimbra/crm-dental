package crm_data.commercial.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import crm_data.commercial.service.DocumentRateLimiter;

/** Registra o limite de envios de PDF (opcional: fatias de teste web sem o módulo não têm o limitador). */
@Configuration(proxyBeanMethods = false)
public class CommercialWebConfig implements WebMvcConfigurer {

	private final ObjectProvider<DocumentRateLimiter> limiter;

	public CommercialWebConfig(ObjectProvider<DocumentRateLimiter> limiter) {
		this.limiter = limiter;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		limiter.ifAvailable(l -> registry.addInterceptor(new DocumentRateLimitInterceptor(l))
				.addPathPatterns("/api/v1/budgets/*/send", "/api/v1/budgets/*/documents"));
	}
}
