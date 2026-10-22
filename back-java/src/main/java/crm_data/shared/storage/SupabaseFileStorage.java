package crm_data.shared.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Supabase Storage por REST, com a service key (que ignora as políticas do Storage: o isolamento é o prefixo
 * {@code <clinic_id>/} montado pelo backend). Bucket privado. Upload em streaming, timeouts curtos, sem
 * {@code byte[]} do arquivo. Nenhuma URL, chave ou resposta do Storage vai para mensagem de erro ou log.
 *
 * <p>Rotas usadas ({@code {base}/storage/v1}): {@code POST /object/{bucket}/{key}}, {@code POST
 * /object/sign/{bucket}/{key}} e {@code DELETE /object/{bucket}} com {@code {"prefixes":[...]}}.
 */
public class SupabaseFileStorage implements FileStorage {

	private static final Pattern BUCKET = Pattern.compile("[A-Za-z0-9._-]{1,63}");
	private static final int DELETE_BATCH = 100;

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SignResponse(String signedURL) {
	}

	private final RestClient client;
	private final String storageBase;
	private final String bucket;

	public SupabaseFileStorage(String projectUrl, String serviceKey, String bucket, Duration connectTimeout,
			Duration readTimeout) {
		if (projectUrl == null || projectUrl.isBlank() || serviceKey == null || serviceKey.isBlank()
				|| bucket == null || !BUCKET.matcher(bucket).matches()) {
			throw new StorageException("Configuração do Supabase Storage inválida.");
		}
		this.bucket = bucket;
		this.storageBase = projectUrl.strip().replaceAll("/+$", "") + "/storage/v1";
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);
		this.client = RestClient.builder()
				.requestFactory(factory)
				.defaultHeader("Authorization", "Bearer " + serviceKey)
				.defaultHeader("apikey", serviceKey)
				.build();
	}

	@Override
	public void save(String key, InputStream content, String contentType, long size) throws IOException {
		URI uri = objectUri(key);
		call("gravar", () -> client.post().uri(uri)
				.header("x-upsert", "false")
				.contentType(MediaType.parseMediaType(contentType))
				.contentLength(size)
				.body((StreamingHttpOutputMessage.Body) out -> content.transferTo(out))
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw failure("gravar", response.getStatusCode().value());
				})
				.toBodilessEntity());
	}

	@Override
	public InputStream load(String key) throws IOException {
		URI uri = URI.create(storageBase + "/object/authenticated/" + bucket + "/" + StorageKeys.require(key));
		byte[] body = call("ler", () -> client.get().uri(uri).retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw failure("ler", response.getStatusCode().value());
				})
				.body(byte[].class));
		return new ByteArrayInputStream(body == null ? new byte[0] : body);
	}

	@Override
	public void delete(String key) throws IOException {
		deleteAll(List.of(key));
	}

	@Override
	public void deleteAll(Collection<String> keys) throws IOException {
		List<String> all = new ArrayList<>(keys.size());
		keys.forEach(key -> all.add(StorageKeys.require(key)));
		URI uri = URI.create(storageBase + "/object/" + bucket);
		for (int from = 0; from < all.size(); from += DELETE_BATCH) {
			List<String> batch = all.subList(from, Math.min(from + DELETE_BATCH, all.size()));
			call("remover", () -> client.method(HttpMethod.DELETE).uri(uri)
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("prefixes", batch))
					.retrieve()
					.onStatus(HttpStatusCode::isError, (request, response) -> {
						throw failure("remover", response.getStatusCode().value());
					})
					.toBodilessEntity());
		}
	}

	@Override
	public URI createSignedUrl(String key, Duration ttl) throws IOException {
		URI uri = URI.create(storageBase + "/object/sign/" + bucket + "/" + StorageKeys.require(key));
		SignResponse response = call("assinar", () -> client.post().uri(uri)
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("expiresIn", ttl.toSeconds()))
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, res) -> {
					throw failure("assinar", res.getStatusCode().value());
				})
				.body(SignResponse.class));
		if (response == null || response.signedURL() == null || !response.signedURL().startsWith("/object/sign/")) {
			throw new StorageException("Resposta inesperada do Storage ao assinar.");
		}
		return URI.create(storageBase + response.signedURL());
	}

	private URI objectUri(String key) {
		return URI.create(storageBase + "/object/" + bucket + "/" + StorageKeys.require(key));
	}

	private static StorageException failure(String operation, int status) {
		return new StorageException("Falha ao " + operation + " no Storage (HTTP " + status + ").");
	}

	/** Executa a chamada convertendo falhas de rede/protocolo em {@link StorageException} sem URL na mensagem. */
	private static <T> T call(String operation, java.util.function.Supplier<T> action) {
		try {
			return action.get();
		}
		catch (StorageException ex) {
			throw ex;
		}
		catch (RestClientException | java.io.UncheckedIOException ex) {
			throw new StorageException("Storage indisponível ao " + operation + " (" + ex.getClass().getSimpleName() + ").");
		}
	}

}
