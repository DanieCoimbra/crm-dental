package crm_data.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.LocalFileStorage;
import crm_data.shared.storage.StorageKeys;
import crm_data.shared.storage.SupabaseFileStorage;

class StorageConfigTest {

	@Configuration
	@EnableConfigurationProperties(CrmProperties.class)
	@Import(StorageConfig.class)
	static class Wiring {
	}

	/** Consumidor por tipo, como o {@code PatientFileService}: precisa receber o bean dos exames (primário). */
	record ByType(FileStorage storage) {
	}

	record ByQualifier(@Qualifier(StorageConfig.DOCUMENT_STORAGE) FileStorage storage) {
	}

	@Configuration
	static class Consumers {
		@Bean
		ByType byType(FileStorage storage) {
			return new ByType(storage);
		}

		@Bean
		ByQualifier byQualifier(@Qualifier(StorageConfig.DOCUMENT_STORAGE) FileStorage storage) {
			return new ByQualifier(storage);
		}
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class,
			Consumers.class);

	@Test
	void devAndTestUseTheLocalStorageForBothAndAnInjectionByTypeIsNotAmbiguous(@TempDir Path dir) {
		runner.withPropertyValues("crm.storage.local-dir=" + dir.toString().replace('\\', '/')).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean("fileStorage")).isInstanceOf(LocalFileStorage.class);
			assertThat(context.getBean(StorageConfig.DOCUMENT_STORAGE)).isInstanceOf(LocalFileStorage.class);
			assertThat(context.getBean(ByType.class).storage()).isSameAs(context.getBean("fileStorage"));
			assertThat(context.getBean(ByQualifier.class).storage()).isSameAs(context.getBean(StorageConfig.DOCUMENT_STORAGE));
			assertThat(context.getBean("fileStorage")).isNotSameAs(context.getBean(StorageConfig.DOCUMENT_STORAGE));
		});
	}

	@Test
	void anObjectWrittenByOneBeanIsVisibleToTheOtherBecauseTheyShareTheLocalDirectory(@TempDir Path dir) {
		runner.withPropertyValues("crm.storage.local-dir=" + dir.toString().replace('\\', '/')).run(context -> {
			LocalFileStorage documents = (LocalFileStorage) context.getBean(StorageConfig.DOCUMENT_STORAGE);
			LocalFileStorage exams = (LocalFileStorage) context.getBean("fileStorage");
			String key = StorageKeys.ofBudget(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

			documents.save(key, new ByteArrayInputStream(new byte[] { 1, 2, 3 }), "application/pdf", 3);

			assertThat(exams.exists(key)).isTrue();
		});
	}

	@Test
	void withoutALocalDirBothBeansStillShareTheSameTemporaryDirectory() {
		runner.run(context -> {
			LocalFileStorage documents = (LocalFileStorage) context.getBean(StorageConfig.DOCUMENT_STORAGE);
			LocalFileStorage exams = (LocalFileStorage) context.getBean("fileStorage");
			String key = StorageKeys.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

			exams.save(key, new ByteArrayInputStream(new byte[] { 9 }), "image/png", 1);
			try {
				assertThat(documents.exists(key)).isTrue();
			}
			finally {
				exams.delete(key);
			}
		});
	}

	@Test
	void withASupabaseUrlBothBeansTalkToSupabase() {
		runner.withPropertyValues("crm.storage.url=https://x.supabase.co", "crm.storage.service-key=k",
				"crm.storage.bucket=exames", "crm.documents.upload-timeout=7s").run(context -> {
					assertThat(context.getBean("fileStorage")).isInstanceOf(SupabaseFileStorage.class);
					assertThat(context.getBean(StorageConfig.DOCUMENT_STORAGE)).isInstanceOf(SupabaseFileStorage.class);
					assertThat(context.getBean(CrmProperties.class).documents().uploadTimeout()).isEqualTo(Duration.ofSeconds(7));
				});
	}

	@Test
	void documentLimitsHaveDefaultsWhenNothingIsConfigured() {
		CrmProperties properties = new CrmProperties(List.of(), null, false, null);

		assertThat(properties.documents().uploadTimeout()).isEqualTo(Duration.ofSeconds(10));
		assertThat(properties.documents().maxConcurrent()).isEqualTo(3);
		assertThat(properties.documents().sendsPerMinute()).isEqualTo(6);
		assertThat(properties.documents().transactionTimeout()).isEqualTo(Duration.ofSeconds(20));
	}

	@Test
	void invalidDocumentLimitsFallBackToTheDefaults() {
		CrmProperties.Documents documents = new CrmProperties.Documents(null, 0, -1, null);

		assertThat(documents.maxConcurrent()).isEqualTo(3);
		assertThat(documents.sendsPerMinute()).isEqualTo(6);
	}

	@Test
	void documentLimitsBindFromTheYamlProperties() {
		runner.withPropertyValues("crm.documents.upload-timeout=4s", "crm.documents.max-concurrent=2",
				"crm.documents.sends-per-minute=9", "crm.documents.transaction-timeout=15s").run(context -> {
					CrmProperties.Documents documents = context.getBean(CrmProperties.class).documents();
					assertThat(documents.uploadTimeout()).isEqualTo(Duration.ofSeconds(4));
					assertThat(documents.maxConcurrent()).isEqualTo(2);
					assertThat(documents.sendsPerMinute()).isEqualTo(9);
					assertThat(documents.transactionTimeout()).isEqualTo(Duration.ofSeconds(15));
				});
	}
}
