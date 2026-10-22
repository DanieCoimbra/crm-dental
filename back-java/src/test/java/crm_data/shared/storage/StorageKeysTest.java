package crm_data.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StorageKeysTest {

	private final UUID clinic = UUID.randomUUID();
	private final UUID patient = UUID.randomUUID();
	private final UUID file = UUID.randomUUID();

	@Test
	void buildsClinicPatientFileKey() {
		String key = StorageKeys.of(clinic, patient, file);

		assertThat(key).isEqualTo(clinic + "/" + patient + "/" + file);
		assertThatCode(() -> StorageKeys.require(key)).doesNotThrowAnyException();
		assertThat(StorageKeys.belongsTo(key, clinic)).isTrue();
		assertThat(StorageKeys.belongsTo(key, UUID.randomUUID())).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "../etc/passwd", "a/b/c", "raio-x.png",
			"11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222",
			"11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/33333333-3333-3333-3333-333333333333/extra",
			"11111111-1111-1111-1111-111111111111//33333333-3333-3333-3333-333333333333",
			"11111111-1111-1111-1111-111111111111/../33333333-3333-3333-3333-333333333333/x",
			"/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/33333333-3333-3333-3333-333333333333",
			"11111111-1111-1111-1111-11111111111G/22222222-2222-2222-2222-222222222222/33333333-3333-3333-3333-333333333333" })
	void rejectsAnythingThatIsNotThreeCanonicalUuids(String key) {
		assertThatThrownBy(() -> StorageKeys.require(key)).isInstanceOf(StorageException.class);
	}

	@Test
	void belongsToIsFalseForNull() {
		assertThat(StorageKeys.belongsTo(null, clinic)).isFalse();
	}
}
