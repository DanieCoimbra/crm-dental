package crm_data.record.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import crm_data.record.service.UploadRateLimiter;

/** Registra o limite de uploads (opcional: fatias de teste web sem o módulo não têm o limitador). */
@Configuration(proxyBeanMethods = false)
public class RecordWebConfig implements WebMvcConfigurer {

	private final ObjectProvider<UploadRateLimiter> limiter;

	public RecordWebConfig(ObjectProvider<UploadRateLimiter> limiter) {
		this.limiter = limiter;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		limiter.ifAvailable(
				l -> registry.addInterceptor(new UploadRateLimitInterceptor(l)).addPathPatterns("/api/v1/patients/*/files"));
	}
}
