package crm_data.support;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import crm_data.shared.config.StorageConfig;
import crm_data.shared.storage.FileStorage;

/**
 * Troca o bean {@code documentStorage} por um {@link FaultyFileStorage} que decora o original (sem sobrescrever a
 * definição do bean). O bean dos exames não muda. Importe esta configuração no IT que precisa injetar falhas no envio do
 * PDF; o contexto desse IT é próprio, mas reaproveita o mesmo contêiner PostgreSQL.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FaultyDocumentStorageConfig {

	@Bean
	static BeanPostProcessor faultyDocumentStorage() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (StorageConfig.DOCUMENT_STORAGE.equals(beanName) && bean instanceof FileStorage storage) {
					return new FaultyFileStorage(storage);
				}
				return bean;
			}
		};
	}
}
