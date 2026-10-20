package crm_data.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import crm_data.shared.crypto.CryptoException.Reason;

class KeyRingTest {

	private static String key(int bytes) {
		byte[] raw = new byte[bytes];
		new SecureRandom().nextBytes(raw);
		return Base64.getEncoder().encodeToString(raw);
	}

	private static final String K1 = key(32);
	private static final String K2 = key(32);

	private static void assertInvalid(String keys, String active, String mustMention, String... mustNotLeak) {
		assertThatThrownBy(() -> KeyRing.parse(keys, active))
				.isInstanceOfSatisfying(CryptoException.class, ex -> {
					assertThat(ex.reason()).isEqualTo(Reason.KEY_INVALID);
					assertThat(ex.getMessage()).contains(mustMention);
					for (String secret : mustNotLeak) {
						assertThat(ex.getMessage()).doesNotContain(secret);
					}
					assertThat(ex.getCause()).isNull();
				});
	}

	@Test
	void parsesMultipleVersionsAndKeepsTheOldOnesForReading() {
		KeyRing ring = KeyRing.parse("1:" + K1 + ", 2:" + K2, "2");

		assertThat(ring.activeVersion()).isEqualTo(2);
		assertThat(ring.active().getEncoded()).isEqualTo(Base64.getDecoder().decode(K2));
		assertThat(ring.byVersion(1).getEncoded()).isEqualTo(Base64.getDecoder().decode(K1));
	}

	@Test
	void unknownVersionIsReportedWithTheVersionOnly() {
		KeyRing ring = KeyRing.parse("1:" + K1, "1");

		assertThatThrownBy(() -> ring.byVersion(9)).isInstanceOfSatisfying(CryptoException.class, ex -> {
			assertThat(ex.reason()).isEqualTo(Reason.VERSION_UNKNOWN);
			assertThat(ex.getMessage()).contains("9");
		});
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	void missingKeysAbort(String keys) {
		assertInvalid(keys, "1", "CRM_AES_KEYS ausente");
	}

	@ParameterizedTest
	@NullAndEmptySource
	void missingActiveVersionAborts(String active) {
		assertInvalid("1:" + K1, active, "CRM_AES_ACTIVE_VERSION ausente");
	}

	@Test
	void sixteenByteKeyIsRejectedWithoutLeakingTheKey() {
		String shortKey = key(16);
		assertInvalid("1:" + shortKey, "1", "versão 1 deve ter 32 bytes", shortKey);
	}

	@Test
	void keyLongerThan32BytesIsRejected() {
		assertInvalid("1:" + key(48), "1", "32 bytes");
	}

	@Test
	void invalidBase64IsRejectedWithoutLeakingTheValue() {
		assertInvalid("1:%%%segredo-nao-base64%%%", "1", "não é base64 válido", "segredo-nao-base64");
	}

	@Test
	void activeVersionMissingFromTheRingAborts() {
		assertInvalid("1:" + K1, "2", "versão 2 não existe");
	}

	@Test
	void duplicatedVersionAborts() {
		assertInvalid("1:" + K1 + ",1:" + K2, "1", "versão 1 repetida");
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "256", "-1", "abc", "1.5" })
	void versionOutsideOneTo255OrNotAnIntegerAborts(String version) {
		assertInvalid(version + ":" + K1, version, "entre 1 e 255");
		assertInvalid("1:" + K1, version, "entre 1 e 255");
	}

	@Test
	void itemWithoutSeparatorAborts() {
		assertInvalid(K1, "1", "<versão>:<base64>", K1);
	}

	@Test
	void trailingCommaAborts() {
		assertInvalid("1:" + K1 + ",", "1", "<versão>:<base64>");
	}

	@Test
	void toStringNeverContainsKeyMaterial() {
		String text = KeyRing.parse("1:" + K1 + ",2:" + K2, "2").toString();

		assertThat(text).contains("versions=[1, 2]", "active=2").doesNotContain(K1, K2);
	}

	// ---- Startup (CryptoConfig) ----

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CryptoConfig.class);

	@Test
	void startupSucceedsWithAValidKeyRing() {
		runner.withPropertyValues("crm.crypto.keys=1:" + K1 + ",2:" + K2, "crm.crypto.active-version=2")
				.run(context -> assertThat(context.getBean(KeyRing.class).activeVersion()).isEqualTo(2));
	}

	@Test
	void startupAbortsWithoutKeysAndWithoutPrintingAnyKey() {
		runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(rootMessages(context.getStartupFailure())).contains("CRM_AES_KEYS ausente");
		});
	}

	@Test
	void startupAbortsWith16ByteKeyAndNeverPrintsIt() {
		String shortKey = key(16);
		runner.withPropertyValues("crm.crypto.keys=1:" + shortKey, "crm.crypto.active-version=1").run(context -> {
			assertThat(context).hasFailed();
			String messages = rootMessages(context.getStartupFailure());
			assertThat(messages).contains("32 bytes").doesNotContain(shortKey);
		});
	}

	@Test
	void startupAbortsWhenTheActiveVersionIsNotInTheRing() {
		runner.withPropertyValues("crm.crypto.keys=1:" + K1, "crm.crypto.active-version=3").run(context -> {
			assertThat(context).hasFailed();
			assertThat(rootMessages(context.getStartupFailure())).contains("versão 3 não existe").doesNotContain(K1);
		});
	}

	private static String rootMessages(Throwable failure) {
		StringBuilder messages = new StringBuilder();
		for (Throwable t = failure; t != null; t = t.getCause()) {
			messages.append(t.getMessage()).append('\n');
		}
		return messages.toString();
	}
}
