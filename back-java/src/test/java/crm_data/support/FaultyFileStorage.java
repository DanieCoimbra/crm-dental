package crm_data.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import crm_data.shared.storage.FileStorage;

/**
 * Decora um {@link FileStorage} real (o {@code LocalFileStorage} dos ITs) com falhas e latência configuráveis e com o
 * registro do que foi gravado e apagado. A falha é explícita e ligada por cada teste; sem ela o comportamento é o do
 * armazenamento decorado. As listas guardam chaves de teste (UUIDs), nunca dado de paciente.
 */
public class FaultyFileStorage implements FileStorage {

	private final FileStorage delegate;

	public volatile boolean failOnSave;
	public volatile boolean failOnDelete;
	public volatile long saveLatencyMillis;

	/** Chaves que o {@code save} gravou com sucesso. */
	public final List<String> saved = new CopyOnWriteArrayList<>();
	/** Chaves que o {@code delete} tentou apagar (inclusive as que falharam). */
	public final List<String> deleted = new CopyOnWriteArrayList<>();

	public FaultyFileStorage(FileStorage delegate) {
		this.delegate = delegate;
	}

	/** Volta ao estado normal e limpa os registros. */
	public void reset() {
		failOnSave = false;
		failOnDelete = false;
		saveLatencyMillis = 0;
		saved.clear();
		deleted.clear();
	}

	@Override
	public void save(String key, InputStream content, String contentType, long size) throws IOException {
		if (saveLatencyMillis > 0) {
			try {
				Thread.sleep(saveLatencyMillis);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IOException("interrompido", ex);
			}
		}
		if (failOnSave) {
			throw new IOException("falha injetada no save");
		}
		delegate.save(key, content, contentType, size);
		saved.add(key);
	}

	@Override
	public InputStream load(String key) throws IOException {
		return delegate.load(key);
	}

	@Override
	public void delete(String key) throws IOException {
		deleted.add(key);
		if (failOnDelete) {
			throw new IOException("falha injetada no delete");
		}
		delegate.delete(key);
	}

	@Override
	public URI createSignedUrl(String key, Duration ttl) throws IOException {
		return delegate.createSignedUrl(key, ttl);
	}
}
