package crm_data.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.sun.net.httpserver.HttpServer;

import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.StorageException;
import crm_data.shared.storage.StorageKeys;

/**
 * O envio do PDF segura uma conexão do pool de 10 enquanto grava no Storage (H1): o bean {@code documentStorage} precisa
 * desistir em {@code crm.documents.upload-timeout} (10 s por padrão), muito antes dos 30 s do bean dos exames. Prova o
 * efeito, não só a leitura da propriedade, contra um servidor HTTP que demora a responder.
 */
class DocumentStorageTimeoutTest {

	@Configuration
	@EnableConfigurationProperties(CrmProperties.class)
	@Import(StorageConfig.class)
	static class Wiring {
	}

	private static final long SLOW_MILLIS = 2_500;

	private HttpServer server;
	private final AtomicInteger requests = new AtomicInteger();
	private final String key = StorageKeys.ofBudget(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

	@BeforeEach
	void startSlowStorage() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			try {
				Thread.sleep(SLOW_MILLIS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			byte[] body = "{}".getBytes();
			try {
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
			catch (IOException ex) {
				// o cliente já desistiu: é o esperado no caso do timeout curto
			}
			finally {
				exchange.close();
			}
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private ApplicationContextRunner runner(String uploadTimeout) {
		return new ApplicationContextRunner().withUserConfiguration(Wiring.class).withPropertyValues(
				"crm.storage.url=http://127.0.0.1:" + server.getAddress().getPort(), "crm.storage.service-key=chave-de-teste",
				"crm.storage.bucket=exames", "crm.documents.upload-timeout=" + uploadTimeout);
	}

	@Test
	void theDocumentStorageGivesUpAtTheUploadTimeoutWithAMessageWithoutKeyOrUrl() {
		runner("1s").run(context -> {
			FileStorage documents = (FileStorage) context.getBean(StorageConfig.DOCUMENT_STORAGE);
			long started = System.nanoTime();

			assertThatThrownBy(() -> documents.save(key, new ByteArrayInputStream(new byte[] { 1, 2, 3 }), "application/pdf", 3))
					.isInstanceOfSatisfying(StorageException.class,
							ex -> assertThat(ex.getMessage()).doesNotContain(key, "127.0.0.1", "chave-de-teste"));

			long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
			assertThat(elapsed).as("desistiu no timeout de 1 s, não esperou os %d ms do servidor", SLOW_MILLIS).isLessThan(SLOW_MILLIS - 500);
			assertThat(requests.get()).isEqualTo(1);
		});
	}

	@Test
	void theExamStorageKeepsItsLongTimeoutAndWaitsForTheSlowServer() {
		runner("1s").run(context -> {
			FileStorage exams = context.getBean("fileStorage", FileStorage.class);
			long started = System.nanoTime();

			exams.save(key, new ByteArrayInputStream(new byte[] { 1, 2, 3 }), "application/pdf", 3);

			long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
			assertThat(elapsed).as("o bean dos exames (30 s) espera a resposta lenta").isGreaterThanOrEqualTo(SLOW_MILLIS - 200);
		});
	}

	@Test
	void theDefaultUploadTimeoutIsTenSeconds() {
		assertThat(new CrmProperties(java.util.List.of(), null, false, null).documents().uploadTimeout()).isEqualTo(Duration.ofSeconds(10));
	}
}
