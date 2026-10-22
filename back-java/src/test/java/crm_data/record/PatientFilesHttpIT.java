package crm_data.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import crm_data.support.DatabaseContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Servidor HTTP de verdade (Tomcat): o que o MockMvc não alcança. Prova o limite de 10 MB do multipart, que vira 413
 * em {@code application/problem+json}, e que o limite de uploads por minuto roda antes de o corpo multipart ser lido
 * ({@code spring.servlet.multipart.resolve-lazily=true}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PatientFilesHttpIT {

	private static final String BOUNDARY = "----crmBoundary7MA4YWxkTrZu0gW";
	private static final int TEN_MB = 10_485_760;
	private static final byte[] PNG_HEAD = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@LocalServerPort
	int port;
	@Autowired
	JsonMapper mapper;

	private final HttpClient http = HttpClient.newHttpClient();

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	private HttpResponse<String> send(HttpRequest request) throws Exception {
		return http.send(request, BodyHandlers.ofString());
	}

	/** Registra uma clínica nova e devolve o access token do dentista. */
	private String register() throws Exception {
		String body = "{\"clinicName\":\"Clínica %s\",\"name\":\"Dra. Teste\",\"email\":\"%s@teste.local\",\"password\":\"SenhaForte2026\"}"
				.formatted(UUID.randomUUID(), UUID.randomUUID());
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/register")))
				.header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)).build());
		assertThat(response.statusCode()).isEqualTo(201);
		return mapper.readTree(response.body()).get("accessToken").asString();
	}

	private String createPatient(String token) throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url("/api/v1/patients")))
				.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
				.POST(BodyPublishers.ofString("{\"name\":\"Maria Souza\",\"birthDate\":\"1988-04-12\",\"phone\":\"11987654321\"}")).build());
		assertThat(response.statusCode()).isEqualTo(201);
		return mapper.readTree(response.body()).get("id").asString();
	}

	private static byte[] multipartBody(byte[] fileBytes) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(fileBytes.length + 512);
		out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"kind\"\r\n\r\nOTHER\r\n").getBytes(StandardCharsets.UTF_8));
		out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"raio-x.png\"\r\n"
				+ "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		out.writeBytes(fileBytes);
		out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	private HttpRequest.Builder upload(String token, String patient) {
		return HttpRequest.newBuilder(URI.create(url("/api/v1/patients/" + patient + "/files")))
				.header("Authorization", "Bearer " + token);
	}

	@Test
	void aRealMultipartUploadIsAccepted() throws Exception {
		String token = register();
		String patient = createPatient(token);

		HttpResponse<String> response = send(upload(token, patient)
				.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
				.POST(BodyPublishers.ofByteArray(multipartBody(Arrays.copyOf(PNG_HEAD, 2048)))).build());

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(mapper.readTree(response.body()).get("id").asString()).isNotBlank();
	}

	@Test
	void aFileOverTenMegabytesIsRejectedByTheServerWith413ProblemJson() throws Exception {
		String token = register();
		String patient = createPatient(token);

		HttpResponse<String> response = send(upload(token, patient)
				.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
				.POST(BodyPublishers.ofByteArray(multipartBody(Arrays.copyOf(PNG_HEAD, TEN_MB + 1)))).build());

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
		assertThat(response.body()).doesNotContain("FileSizeLimitExceeded", "org.apache");
	}

	@Test
	void theUploadLimitRunsBeforeTheMultipartBodyIsParsed() throws Exception {
		String token = register();
		String patient = createPatient(token);
		// corpo multipart sem boundary: se o parse fosse antecipado, nunca chegaria ao limitador (o 11º seria 400, não 429)
		HttpRequest.Builder garbage = upload(token, patient).header("Content-Type", "multipart/form-data")
				.POST(BodyPublishers.ofString("lixo que nao e multipart"));

		for (int i = 0; i < 10; i++) {
			assertThat(send(garbage.build()).statusCode()).as("requisição %d", i + 1).isNotEqualTo(429);
		}
		HttpResponse<String> eleventh = send(garbage.build());

		assertThat(eleventh.statusCode()).isEqualTo(429);
		assertThat(eleventh.headers().firstValue("Retry-After")).isPresent();
	}
}
