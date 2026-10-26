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

	@Test
	void buildsAndAcceptsTheBudgetPdfKeyAndItFitsTheDatabaseColumn() {
		String key = StorageKeys.ofBudget(clinic, patient, file);

		assertThat(key).isEqualTo(clinic + "/" + patient + "/budgets/" + file);
		assertThat(key).hasSize(118); // varchar(120) em budget_documents.storage_key
		assertThatCode(() -> StorageKeys.require(key)).doesNotThrowAnyException();
		assertThat(StorageKeys.belongsTo(key, clinic)).isTrue();
		assertThat(StorageKeys.belongsTo(key, UUID.randomUUID())).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/budgets/../33333333-3333-3333-3333-333333333333",
			"11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/budgets/",
			"11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/budgets",
			"11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/budget/33333333-3333-3333-3333-333333333333",
			"11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/BUDGETS/33333333-3333-3333-3333-333333333333",
			"11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/budgets/33333333-3333-3333-3333-333333333333/x",
			"11111111-1111-1111-1111-111111111111/budgets/22222222-2222-2222-2222-222222222222/33333333-3333-3333-3333-333333333333",
			"11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/budgets/33333333-3333-3333-3333-33333333333G",
			"11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/budgets/33333333-3333-3333-3333-333333333333\n" })
	void rejectsMalformedBudgetKeys(String key) {
		assertThatThrownBy(() -> StorageKeys.require(key)).isInstanceOf(StorageException.class);
	}

	@Test
	void rejectsUppercaseUuidsInBothForms() {
		String upper = file.toString().toUpperCase();

		assertThatThrownBy(() -> StorageKeys.require(clinic + "/" + patient + "/" + upper)).isInstanceOf(StorageException.class);
		assertThatThrownBy(() -> StorageKeys.require(clinic + "/" + patient + "/budgets/" + upper))
				.isInstanceOf(StorageException.class);
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
