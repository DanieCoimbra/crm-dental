package crm_data.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import crm_data.shared.tenant.TenantJpaTransactionManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Persistência multi-tenant. O datasource do papel de aplicação (e o limite de 10 conexões do Hikari) vêm das
 * propriedades {@code spring.datasource.*}; aqui só se troca o gerenciador de transações pelo que aplica o tenant.
 */
@Configuration(proxyBeanMethods = false)
public class TenantPersistenceConfig {

	@Bean
	PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
		return new TenantJpaTransactionManager(entityManagerFactory);
	}
}
