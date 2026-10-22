package crm_data.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;

/**
 * Armazenamento em disco para desenvolvimento e testes; não existe em {@code prod} (o validador de subida exige o
 * Supabase). O conteúdo é gravado em streaming; a URL "assinada" é fictícia e só serve para exercitar o fluxo.
 */
public class LocalFileStorage implements FileStorage {

	private final Path root;

	public LocalFileStorage(Path root) {
		this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
	}

	@Override
	public void save(String key, InputStream content, String contentType, long size) throws IOException {
		Path target = resolve(key);
		Files.createDirectories(target.getParent());
		Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
		try {
			Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
			Files.move(temp, target); // não sobrescreve: falha se a chave já existe
		}
		finally {
			Files.deleteIfExists(temp);
		}
	}

	@Override
	public InputStream load(String key) throws IOException {
		try {
			return Files.newInputStream(resolve(key));
		}
		catch (NoSuchFileException ex) {
			throw new StorageException("Objeto não encontrado.");
		}
	}

	@Override
	public void delete(String key) throws IOException {
		Files.deleteIfExists(resolve(key));
	}

	@Override
	public URI createSignedUrl(String key, Duration ttl) {
		StorageKeys.require(key);
		return URI.create("local://storage/" + key + "?expiresIn=" + ttl.toSeconds());
	}

	/** Só para testes: o objeto existe no disco? */
	public boolean exists(String key) {
		return Files.exists(resolve(key));
	}

	private Path resolve(String key) {
		Path path = root.resolve(StorageKeys.require(key)).normalize();
		if (!path.startsWith(root)) {
			throw new StorageException("Chave de armazenamento inválida.");
		}
		return path;
	}
}
