package crm_data.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

	@TempDir
	Path root;

	LocalFileStorage storage;
	String key;

	@BeforeEach
	void setUp() {
		storage = new LocalFileStorage(root);
		key = StorageKeys.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
	}

	private long filesOnDisk() throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).count();
		}
	}

	@Test
	void savesLoadsAndDeletes() throws IOException {
		storage.save(key, new ByteArrayInputStream("conteúdo".getBytes()), "image/png", 9);

		assertThat(storage.exists(key)).isTrue();
		assertThat(storage.load(key).readAllBytes()).isEqualTo("conteúdo".getBytes());

		storage.delete(key);
		assertThat(storage.exists(key)).isFalse();
		assertThatCode(() -> storage.delete(key)).doesNotThrowAnyException(); // idempotente
	}

	@Test
	void doesNotOverwriteAnExistingObjectAndLeavesNoTempFile() throws IOException {
		storage.save(key, new ByteArrayInputStream("um".getBytes()), "image/png", 2);

		assertThatThrownBy(() -> storage.save(key, new ByteArrayInputStream("dois".getBytes()), "image/png", 4))
				.isInstanceOf(IOException.class);

		assertThat(storage.load(key).readAllBytes()).isEqualTo("um".getBytes());
		assertThat(filesOnDisk()).isEqualTo(1);
	}

	@Test
	void deleteAllRemovesEveryKey() throws IOException {
		String other = StorageKeys.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		storage.save(key, new ByteArrayInputStream("a".getBytes()), "image/png", 1);
		storage.save(other, new ByteArrayInputStream("b".getBytes()), "image/png", 1);

		storage.deleteAll(List.of(key, other));

		assertThat(filesOnDisk()).isZero();
	}

	@Test
	void rejectsKeysOutsideThePatternOnEveryOperation() {
		for (String bad : List.of("../fora", "a/b/c", "")) {
			assertThatThrownBy(() -> storage.save(bad, new ByteArrayInputStream(new byte[1]), "image/png", 1))
					.isInstanceOf(StorageException.class);
			assertThatThrownBy(() -> storage.load(bad)).isInstanceOf(StorageException.class);
			assertThatThrownBy(() -> storage.delete(bad)).isInstanceOf(StorageException.class);
			assertThatThrownBy(() -> storage.createSignedUrl(bad, Duration.ofSeconds(60))).isInstanceOf(StorageException.class);
		}
	}

	@Test
	void loadOfAMissingObjectIsAStorageException() {
		assertThatThrownBy(() -> storage.load(key)).isInstanceOf(StorageException.class);
	}

	@Test
	void signedUrlCarriesTheTtl() {
		assertThat(storage.createSignedUrl(key, Duration.ofSeconds(60)).toString()).endsWith(key + "?expiresIn=60");
	}
}
