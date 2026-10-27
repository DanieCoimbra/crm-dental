package crm_data.document.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registra o renderizador do PDF (classe pura, sem Spring) como bean, para ser substituído nos testes de falha. */
@Configuration(proxyBeanMethods = false)
class DocumentConfig {

	@Bean
	DocumentPdfRenderer documentPdfRenderer() {
		return new DocumentPdfRenderer();
	}
}
