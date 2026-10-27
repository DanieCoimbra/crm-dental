package crm_data.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import crm_data.shared.crypto.AesGcmCipher;
import crm_data.shared.crypto.KeyRing;

class DocumentCipherTest {

	private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

	private final DocumentCipher cipher = new DocumentCipher(new AesGcmCipher(KeyRing.parse("1:" + KEY, "1")));

	@Test
	void roundTripKeepsAccentsAndLineBreaks() {
		String text = "Amoxicilina 500 mg\n8/8 h por 7 dias — após as refeições.";

		String stored = cipher.encrypt(text);

		assertThat(stored).doesNotContain("Amoxicilina").matches("^[A-Za-z0-9+/]+={0,2}$");
		assertThat(cipher.decrypt(stored)).isEqualTo(text);
	}

	@Test
	void emptyOrNullTextBecomesTheFortyCharacterCipherOfEmpty() {
		assertThat(cipher.encrypt(null)).hasSize(40);
		assertThat(cipher.encrypt("")).hasSize(40);
		assertThat(cipher.decrypt(cipher.encrypt(null))).isEmpty();
	}

	@Test
	void eachEncryptionUsesAFreshIv() {
		assertThat(cipher.encrypt("igual")).isNotEqualTo(cipher.encrypt("igual"));
	}
}
