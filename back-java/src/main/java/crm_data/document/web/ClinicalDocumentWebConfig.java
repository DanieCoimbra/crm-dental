package crm_data.document.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import crm_data.document.service.ClinicalDocumentRateLimiter;

/** Registra o limite de emissões e de PDFs (opcional: fatias de teste web sem o módulo não têm o limitador). */
@Configuration(proxyBeanMethods = false)
public class ClinicalDocumentWebConfig implements WebMvcConfigurer {

	private final ObjectProvider<ClinicalDocumentRateLimiter> limiter;

	public ClinicalDocumentWebConfig(ObjectProvider<ClinicalDocumentRateLimiter> limiter) {
		this.limiter = limiter;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		limiter.ifAvailable(l -> registry.addInterceptor(new ClinicalDocumentRateLimitInterceptor(l))
				.addPathPatterns("/api/v1/patients/*/clinical-documents", "/api/v1/clinical-documents/*/pdf"));
	}
}
