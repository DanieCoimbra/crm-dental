package crm_data.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class EncryptedStringConverterTest {

	private static final String KEY = key();

	private static String key() {
		byte[] raw = new byte[32];
		new SecureRandom().nextBytes(raw);
		return Base64.getEncoder().encodeToString(raw);
	}

	private final EncryptedStringConverter converter = new EncryptedStringConverter(
			new AesGcmCipher(KeyRing.parse("1:" + KEY, "1")));

	@Test
	void roundTripsTextIncludingEmpty() {
		for (String plain : new String[] { "", "prescrição: 2x ao dia", "😀" }) {
			String column = converter.convertToDatabaseColumn(plain);
			assertThat(column).isNotEqualTo(plain);
			assertThat(converter.convertToEntityAttribute(column)).isEqualTo(plain);
		}
	}

	@Test
	void nullStaysNullBothWays() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}
}
