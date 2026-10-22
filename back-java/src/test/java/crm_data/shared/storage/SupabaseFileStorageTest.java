package crm_data.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Exercita o cliente REST contra um servidor HTTP falso que imita as rotas do Supabase Storage
 * ({@code /storage/v1/object/...}): confere método, caminho, cabeçalhos e corpo de cada chamada e que nenhuma
 * mensagem de erro carrega URL, chave ou resposta do Storage.
 */
class SupabaseFileStorageTest {

	private static final String SERVICE_KEY = "service-key-secreta-de-teste";
	private static final String BUCKET = "exames";

	record Recorded(String method, String path, String authorization, String apikey, String contentType,
			String contentLength, String upsert, byte[] body) {

		String bodyText() {
			return new String(body, StandardCharsets.UTF_8);
		}
	}

	private HttpServer server;
	private final List<Recorded> requests = new CopyOnWriteArrayList<>();
	private volatile Function<Recorded, Reply> responder = r -> new Reply(200, "{}");
	private volatile long delayMillis;
	private SupabaseFileStorage storage;
	private final String key = StorageKeys.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

	record Reply(int status, String body) {
	}

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
		storage = newStorage(Duration.ofSeconds(3), Duration.ofSeconds(5));
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	private SupabaseFileStorage newStorage(Duration connect, Duration read) {
		return new SupabaseFileStorage("http://127.0.0.1:" + server.getAddress().getPort() + "/", SERVICE_KEY, BUCKET,
				connect, read);
	}

	private void handle(HttpExchange exchange) throws IOException {
		byte[] body = exchange.getRequestBody().readAllBytes();
		var h = exchange.getRequestHeaders();
		Recorded recorded = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
				h.getFirst("Authorization"), h.getFirst("apikey"), h.getFirst("Content-Type"), h.getFirst("Content-Length"),
				h.getFirst("x-upsert"), body);
		requests.add(recorded);
		if (delayMillis > 0) {
			try {
				Thread.sleep(delayMillis);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		Reply reply = responder.apply(recorded);
		byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
		if (out.length > 0) {
			exchange.getResponseBody().write(out);
		}
		exchange.close();
	}

	@Test
	void savePostsTheBytesInStreamingWithTypeLengthAndCredentials() throws IOException {
		byte[] content = "PNG-BYTES".getBytes(StandardCharsets.UTF_8);

		storage.save(key, new ByteArrayInputStream(content), "image/png", content.length);

		assertThat(requests).singleElement().satisfies(r -> {
			assertThat(r.method()).isEqualTo("POST");
			assertThat(r.path()).isEqualTo("/storage/v1/object/" + BUCKET + "/" + key);
			assertThat(r.authorization()).isEqualTo("Bearer " + SERVICE_KEY);
			assertThat(r.apikey()).isEqualTo(SERVICE_KEY);
			assertThat(r.contentType()).isEqualTo("image/png");
			assertThat(r.contentLength()).isEqualTo(String.valueOf(content.length));
			assertThat(r.upsert()).isEqualTo("false");
			assertThat(r.body()).isEqualTo(content);
		});
	}

	@Test
	void saveFailureIsAStorageExceptionWithoutUrlKeyOrResponse() {
		responder = r -> new Reply(400, "{\"error\":\"Duplicate\",\"message\":\"segredo-do-storage " + key + "\"}");

		assertThatThrownBy(() -> storage.save(key, new ByteArrayInputStream(new byte[1]), "image/png", 1))
				.isInstanceOf(StorageException.class)
				.satisfies(e -> assertThat(e.getMessage()).contains("HTTP 400")
						.doesNotContain(key, "127.0.0.1", "segredo-do-storage", SERVICE_KEY));
	}

	@Test
	void serverErrorsOnEveryOperationAreStorageExceptions() {
		responder = r -> new Reply(503, "indisponível");

		assertThatThrownBy(() -> storage.save(key, new ByteArrayInputStream(new byte[1]), "image/png", 1)).isInstanceOf(StorageException.class);
		assertThatThrownBy(() -> storage.createSignedUrl(key, Duration.ofSeconds(60))).isInstanceOf(StorageException.class);
		assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(StorageException.class);
		assertThatThrownBy(() -> storage.load(key)).isInstanceOf(StorageException.class);
	}

	@Test
	void createSignedUrlSendsTheTtlAndBuildsTheFinalUrl() throws IOException {
		responder = r -> new Reply(200, "{\"signedURL\":\"/object/sign/" + BUCKET + "/" + key + "?token=abc.def\",\"extra\":1}");

		var url = storage.createSignedUrl(key, Duration.ofSeconds(60));

		assertThat(url.toString()).isEqualTo("http://127.0.0.1:" + server.getAddress().getPort() + "/storage/v1/object/sign/"
				+ BUCKET + "/" + key + "?token=abc.def");
		assertThat(requests).singleElement().satisfies(r -> {
			assertThat(r.method()).isEqualTo("POST");
			assertThat(r.path()).isEqualTo("/storage/v1/object/sign/" + BUCKET + "/" + key);
			assertThat(r.contentType()).startsWith("application/json");
			assertThat(r.bodyText()).isEqualTo("{\"expiresIn\":60}");
			assertThat(r.authorization()).isEqualTo("Bearer " + SERVICE_KEY);
		});
	}

	@Test
	void unexpectedSignResponseIsRejected() {
		for (String body : List.of("{}", "{\"signedURL\":null}", "{\"signedURL\":\"https://evil.example/x\"}", "{\"signedURL\":\"/outra/rota\"}")) {
			responder = r -> new Reply(200, body);
			assertThatThrownBy(() -> storage.createSignedUrl(key, Duration.ofSeconds(60))).isInstanceOf(StorageException.class);
		}
	}

	@Test
	void deleteAllSendsThePrefixesAsJsonInBatchesOfOneHundred() throws IOException {
		List<String> keys = new ArrayList<>();
		for (int i = 0; i < 250; i++) {
			keys.add(StorageKeys.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
		}

		storage.deleteAll(keys);

		assertThat(requests).hasSize(3);
		assertThat(requests).allSatisfy(r -> {
			assertThat(r.method()).isEqualTo("DELETE");
			assertThat(r.path()).isEqualTo("/storage/v1/object/" + BUCKET);
			assertThat(r.contentType()).startsWith("application/json");
		});
		assertThat(requests.get(0).bodyText()).startsWith("{\"prefixes\":[\"" + keys.get(0) + "\"");
		assertThat(requests.get(2).bodyText()).contains(keys.get(249)).doesNotContain(keys.get(0));
	}

	@Test
	void deleteAllWithNothingMakesNoCall() throws IOException {
		storage.deleteAll(List.of());

		assertThat(requests).isEmpty();
	}

	@Test
	void deleteUsesTheSameBatchRoute() throws IOException {
		storage.delete(key);

		assertThat(requests).singleElement().satisfies(r -> {
			assertThat(r.method()).isEqualTo("DELETE");
			assertThat(r.bodyText()).isEqualTo("{\"prefixes\":[\"" + key + "\"]}");
		});
	}

	@Test
	void loadReadsTheObjectBytes() throws IOException {
		responder = r -> new Reply(200, "conteudo");

		assertThat(storage.load(key).readAllBytes()).isEqualTo("conteudo".getBytes(StandardCharsets.UTF_8));
		assertThat(requests).singleElement().satisfies(r -> {
			assertThat(r.method()).isEqualTo("GET");
			assertThat(r.path()).isEqualTo("/storage/v1/object/authenticated/" + BUCKET + "/" + key);
		});
	}

	@Test
	void connectionRefusedIsAStorageExceptionWithoutHostOrKey() {
		server.stop(0);

		assertThatThrownBy(() -> storage.save(key, new ByteArrayInputStream(new byte[1]), "image/png", 1))
				.isInstanceOf(StorageException.class)
				.satisfies(e -> assertThat(e.getMessage()).doesNotContain(key, "127.0.0.1", SERVICE_KEY));
		assertThatThrownBy(() -> storage.createSignedUrl(key, Duration.ofSeconds(60))).isInstanceOf(StorageException.class);
	}

	@Test
	void slowStorageHitsTheReadTimeout() {
		delayMillis = 1500;
		SupabaseFileStorage impatient = newStorage(Duration.ofSeconds(1), Duration.ofMillis(300));

		long start = System.nanoTime();
		assertThatThrownBy(() -> impatient.createSignedUrl(key, Duration.ofSeconds(60))).isInstanceOf(StorageException.class);

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1400));
	}

	@Test
	void keysOutsideThePatternNeverReachTheNetwork() {
		for (String bad : List.of("../x", "a/b/c", "")) {
			assertThatThrownBy(() -> storage.save(bad, new ByteArrayInputStream(new byte[1]), "image/png", 1)).isInstanceOf(StorageException.class);
			assertThatThrownBy(() -> storage.createSignedUrl(bad, Duration.ofSeconds(60))).isInstanceOf(StorageException.class);
			assertThatThrownBy(() -> storage.deleteAll(List.of(bad))).isInstanceOf(StorageException.class);
		}
		assertThat(requests).isEmpty();
	}

	@Test
	void invalidConfigurationIsRejectedAtConstruction() {
		Duration t = Duration.ofSeconds(1);
		assertThatThrownBy(() -> new SupabaseFileStorage("", SERVICE_KEY, BUCKET, t, t)).isInstanceOf(StorageException.class);
		assertThatThrownBy(() -> new SupabaseFileStorage("http://x", "", BUCKET, t, t)).isInstanceOf(StorageException.class);
		assertThatThrownBy(() -> new SupabaseFileStorage("http://x", SERVICE_KEY, "bucket/../x", t, t)).isInstanceOf(StorageException.class);
		assertThatThrownBy(() -> new SupabaseFileStorage("http://x", SERVICE_KEY, null, t, t)).isInstanceOf(StorageException.class);
		assertThatCode(() -> new SupabaseFileStorage("https://proj.supabase.co/", SERVICE_KEY, "exames-privados", t, t))
				.doesNotThrowAnyException();
	}
}
