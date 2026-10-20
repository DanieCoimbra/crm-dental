package crm_data.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import crm_data.support.DatabaseContainer;

/**
 * Servidor Tomcat real (porta aleatória) com {@code server.forward-headers-strategy=native}: o limite por IP deve
 * usar o IP do {@code X-Forwarded-For} enviado por um proxy interno (aqui, o loopback), como o Render fará, e não o
 * IP do proxy. Sem isso todos os clientes dividiriam o mesmo contador.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ForwardedHeadersIT {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@LocalServerPort
	int port;

	private final HttpClient client = HttpClient.newHttpClient();

	private int post(String forwardedFor) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
				.header("Content-Type", "application/json").POST(BodyPublishers.ofString("{}"));
		if (forwardedFor != null) {
			request.header("X-Forwarded-For", forwardedFor);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	@Test
	void ipLimitIsKeyedByTheForwardedClientAddressNotByTheProxy() throws Exception {
		for (int i = 0; i < 20; i++) {
			assertThat(post("203.0.113.10")).as("requisição %d do cliente A", i + 1).isEqualTo(400); // corpo vazio: inválido
		}

		assertThat(post("203.0.113.10")).as("21ª do cliente A").isEqualTo(429);
		assertThat(post("203.0.113.11")).as("cliente B, mesmo proxy, não é afetado").isEqualTo(400);
		assertThat(post("203.0.113.10, 10.0.0.5")).as("cadeia de proxies: vale o cliente original").isEqualTo(429);
	}

	@Test
	void requestsWithoutForwardedHeaderUseThePeerAddress() throws Exception {
		for (int i = 0; i < 20; i++) {
			assertThat(post(null)).isEqualTo(400);
		}

		assertThat(post(null)).isEqualTo(429);
	}
}
