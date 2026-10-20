package crm_data.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import crm_data.shared.crypto.CryptoException.Reason;

class AesGcmCipherTest {

	private static String key() {
		byte[] raw = new byte[32];
		new SecureRandom().nextBytes(raw);
		return Base64.getEncoder().encodeToString(raw);
	}

	private static final String K1 = key();
	private static final String K2 = key();

	private final AesGcmCipher v1 = new AesGcmCipher(KeyRing.parse("1:" + K1, "1"));
	private final AesGcmCipher v2Active = new AesGcmCipher(KeyRing.parse("1:" + K1 + ",2:" + K2, "2"));

	private static CryptoException failure(Runnable action) {
		return (CryptoException) catchThrowable(action);
	}

	private static Throwable catchThrowable(Runnable action) {
		try {
			action.run();
		}
		catch (Throwable t) {
			return t;
		}
		throw new AssertionError("esperava uma exceção");
	}

	// ---- Round-trip e formato ----

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "texto ASCII simples", "Acentuação: ação, coração, órgão, ünïcödé", "Emoji 😀🦷 e 𝒳 fora do BMP",
			"linha1\nlinha2\r\n\ttab", "{\"json\":\"com aspas\"}" })
	void decryptOfEncryptReturnsTheOriginal(String plain) {
		assertThat(v1.decrypt(v1.encrypt(plain))).isEqualTo(plain);
	}

	@Test
	void largeFieldsRoundTrip() {
		String big = "dente ".repeat(64 * 1024 / 6);
		assertThat(v1.decrypt(v1.encrypt(big))).isEqualTo(big);
	}

	@Test
	void nullStaysNullInEveryOperation() {
		assertThat(v1.encrypt(null)).isNull();
		assertThat(v1.decrypt(null)).isNull();
		assertThat(v1.rotate(null)).isNull();
	}

	@Test
	void storedFormatIsVersionIvCiphertextAndTag() {
		String plain = "prontuário";
		byte[] stored = Base64.getDecoder().decode(v2Active.encrypt(plain));

		assertThat(stored[0]).isEqualTo((byte) 2);
		assertThat(stored.length).isEqualTo(1 + 12 + plain.getBytes(StandardCharsets.UTF_8).length + 16);
	}

	@Test
	void emptyTextIsEncryptedAsVersionIvAndTagOnly() {
		assertThat(Base64.getDecoder().decode(v1.encrypt("")).length).isEqualTo(1 + 12 + 16);
	}

	@Test
	void ciphertextDoesNotContainThePlaintext() {
		String plain = "diagnóstico-confidencial";
		String stored = v1.encrypt(plain);
		assertThat(stored).doesNotContain(plain);
		assertThat(new String(Base64.getDecoder().decode(stored), StandardCharsets.ISO_8859_1)).doesNotContain("confidencial");
	}

	// ---- IV aleatório ----

	@Test
	void sameTextEncryptsToDifferentValues() {
		assertThat(v1.encrypt("mesmo texto")).isNotEqualTo(v1.encrypt("mesmo texto"));
	}

	@Test
	void ivsAreNeverRepeatedAcrossManyEncryptions() {
		Set<String> ivs = new HashSet<>();
		for (int i = 0; i < 5000; i++) {
			byte[] stored = Base64.getDecoder().decode(v1.encrypt("x"));
			ivs.add(Base64.getEncoder().encodeToString(Arrays.copyOfRange(stored, 1, 13)));
		}
		assertThat(ivs).hasSize(5000);
	}

	// ---- Adulteração ----

	@Test
	void changingAnySingleBitOfIvCiphertextOrTagIsDetected() {
		byte[] original = Base64.getDecoder().decode(v1.encrypt("dado clínico"));

		for (int index = 1; index < original.length; index++) { // 0 é a versão: coberto à parte
			for (int bit = 0; bit < 8; bit++) {
				byte[] changed = original.clone();
				changed[index] ^= (byte) (1 << bit);
				String stored = Base64.getEncoder().encodeToString(changed);
				assertThat(failure(() -> v1.decrypt(stored)).reason()).as("byte %d bit %d", index, bit).isEqualTo(Reason.TAMPERED);
			}
		}
	}

	@Test
	void changingTheVersionByteNeverYieldsWrongPlaintext() {
		byte[] original = Base64.getDecoder().decode(v2Active.encrypt("dado clínico"));

		original[0] = 1; // existe, mas a chave é outra: a tag não confere
		assertThat(failure(() -> v2Active.decrypt(Base64.getEncoder().encodeToString(original))).reason())
				.isEqualTo(Reason.TAMPERED);

		original[0] = 7; // não existe
		assertThat(failure(() -> v2Active.decrypt(Base64.getEncoder().encodeToString(original))).reason())
				.isEqualTo(Reason.VERSION_UNKNOWN);
	}

	@Test
	void appendedOrTruncatedBytesAreDetected() {
		byte[] original = Base64.getDecoder().decode(v1.encrypt("dado clínico"));

		String truncated = Base64.getEncoder().encodeToString(Arrays.copyOf(original, original.length - 1));
		assertThat(failure(() -> v1.decrypt(truncated)).reason()).isEqualTo(Reason.TAMPERED);

		byte[] longer = Arrays.copyOf(original, original.length + 1);
		assertThat(failure(() -> v1.decrypt(Base64.getEncoder().encodeToString(longer))).reason()).isEqualTo(Reason.TAMPERED);
	}

	@Test
	void wrongKeyForTheSameVersionIsDetected() {
		AesGcmCipher otherKey = new AesGcmCipher(KeyRing.parse("1:" + K2, "1"));
		assertThat(failure(() -> otherKey.decrypt(v1.encrypt("segredo"))).reason()).isEqualTo(Reason.TAMPERED);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "AAAA", "não é base64!!", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" /* 28 bytes: curto demais */ })
	void malformedStoredValuesAreRejected(String stored) {
		assertThat(failure(() -> v1.decrypt(stored)).reason()).isEqualTo(Reason.MALFORMED);
		assertThat(failure(() -> v1.rotate(stored)).reason()).isEqualTo(Reason.MALFORMED);
	}

	@Test
	void exceptionsNeverCarryPlaintextKeysOrStoredValues() {
		String plain = "texto-claro-sigiloso";
		String stored = v1.encrypt(plain);
		byte[] changed = Base64.getDecoder().decode(stored);
		changed[20] ^= 1;
		String tampered = Base64.getEncoder().encodeToString(changed);

		List<CryptoException> errors = List.of(failure(() -> v1.decrypt(tampered)), failure(() -> v1.decrypt("lixo")),
				failure(() -> new AesGcmCipher(KeyRing.parse("2:" + K2, "2")).decrypt(stored)));
		for (CryptoException ex : errors) {
			String everything = ex + " " + ex.getMessage() + " " + Arrays.toString(ex.getStackTrace());
			assertThat(everything).doesNotContain(plain, stored, tampered, K1, K2);
			assertThat(ex.getCause()).isNull();
		}
	}

	// ---- Versões e rotação ----

	@Test
	void dataEncryptedWithVersion1IsReadWhenVersion2IsActive() {
		String oldValue = v1.encrypt("gravado antes da rotação");

		assertThat(v2Active.decrypt(oldValue)).isEqualTo("gravado antes da rotação");
	}

	@Test
	void unknownVersionInTheDataFailsClearly() {
		String valueV2 = v2Active.encrypt("novo");

		CryptoException ex = failure(() -> v1.decrypt(valueV2)); // v1 só conhece a versão 1
		assertThat(ex.reason()).isEqualTo(Reason.VERSION_UNKNOWN);
		assertThat(ex.getMessage()).contains("2");
	}

	@Test
	void rotateRewritesWithTheActiveKey() {
		String oldValue = v1.encrypt("rotacionar");

		String rotated = v2Active.rotate(oldValue);

		assertThat(Base64.getDecoder().decode(rotated)[0]).isEqualTo((byte) 2);
		assertThat(rotated).isNotEqualTo(oldValue);
		assertThat(v2Active.decrypt(rotated)).isEqualTo("rotacionar");
		assertThat(new AesGcmCipher(KeyRing.parse("2:" + K2, "2")).decrypt(rotated)).isEqualTo("rotacionar");
	}

	@Test
	void rotateOfAnAlreadyActiveValueReturnsTheSameValue() {
		String current = v2Active.encrypt("já na versão ativa");
		assertThat(v2Active.rotate(current)).isEqualTo(current);
	}

	@Test
	void rotateOfTamperedOldValueFailsInsteadOfRewritingGarbage() {
		byte[] bytes = Base64.getDecoder().decode(v1.encrypt("dado"));
		bytes[15] ^= 1;
		String tampered = Base64.getEncoder().encodeToString(bytes);

		assertThat(failure(() -> v2Active.rotate(tampered)).reason()).isEqualTo(Reason.TAMPERED);
	}

	// ---- Concorrência e desempenho ----

	@Test
	void isSafeUnderConcurrentUse() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int t = 0; t < 8; t++) {
				int id = t;
				Callable<Boolean> task = () -> {
					for (int i = 0; i < 500; i++) {
						String plain = "thread-" + id + "-valor-" + i;
						if (!plain.equals(v2Active.decrypt(v2Active.encrypt(plain)))) {
							return false;
						}
					}
					return true;
				};
				results.add(pool.submit(task));
			}
			for (Future<Boolean> result : results) {
				assertThat(result.get()).isTrue();
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void tenKilobytesEncryptAndDecryptWithinFiveMilliseconds() {
		String tenKb = "a".repeat(10 * 1024);
		for (int i = 0; i < 200; i++) { // aquecimento da JIT
			v1.decrypt(v1.encrypt(tenKb));
		}
		int rounds = 300;
		long start = System.nanoTime();
		for (int i = 0; i < rounds; i++) {
			v1.decrypt(v1.encrypt(tenKb));
		}
		double averageMs = (System.nanoTime() - start) / 1_000_000.0 / rounds;
		System.out.printf("F03 cifrar+decifrar 10 KB: %.3f ms%n", averageMs);

		assertThat(averageMs).isLessThanOrEqualTo(5.0);
	}

	@Test
	void constructorWithoutRingIsNotPossibleAndKeysAreNotExposedByTheCipher() {
		assertThat(v1.toString()).doesNotContain(K1);
		assertThatThrownBy(() -> KeyRing.parse(null, null)).isInstanceOf(CryptoException.class);
	}
}
