package crm_data.record.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import crm_data.record.service.FileSignature.Type;

class FileNamesTest {

	@Test
	void keepsOnlyTheLastPathSegment() {
		assertThat(FileNames.sanitize("../../etc/passwd", Type.PNG)).isEqualTo("passwd");
		assertThat(FileNames.sanitize("C:\\Users\\ana\\raio-x.png", Type.PNG)).isEqualTo("raio-x.png");
		assertThat(FileNames.sanitize("pasta/sub/foto.jpg", Type.JPEG)).isEqualTo("foto.jpg");
	}

	@Test
	void removesControlAndFormatCharacters() {
		assertThat(FileNames.sanitize("ra\u0000io\n-x\t.png\u202E", Type.PNG)).isEqualTo("raio-x.png");
	}

	@Test
	void normalizesToNfcAndTrims() {
		String decomposed = "  exame-a\u0301.pdf  ";

		String result = FileNames.sanitize(decomposed, Type.PDF);

		assertThat(result).isEqualTo("exame-á.pdf");
		assertThat(Normalizer.isNormalized(result, Normalizer.Form.NFC)).isTrue();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", ".", "..", "...", "/", "../", "dir/", "\u0000\u0001" })
	void unusableNamesBecomeAGenericOneWithTheDetectedExtension(String original) {
		assertThat(FileNames.sanitize(original, Type.WEBP)).isEqualTo("arquivo.webp");
	}

	@Test
	void truncatesTo255CharactersKeepingTheExtension() {
		String result = FileNames.sanitize("a".repeat(400) + ".jpeg", Type.JPEG);

		assertThat(result).hasSize(255).endsWith(".jpeg").startsWith("aaaa");
	}

	@Test
	void doesNotSplitASurrogatePairWhenTruncating() {
		String emoji = "\uD83D\uDE00"; // 2 unidades UTF-16
		String name = "a".repeat(253) + emoji + emoji; // o corte cairia no meio do primeiro par

		String result = FileNames.sanitize(name, Type.PNG);

		assertThat(result.length()).isLessThanOrEqualTo(255);
		assertThat(result.codePoints().allMatch(cp -> cp != 0xFFFD && Character.isValidCodePoint(cp))).isTrue();
		assertThat(Character.isHighSurrogate(result.charAt(result.length() - 1))).isFalse();
	}

	@Test
	void aLongExtensionIsNotTreatedAsOne() {
		String result = FileNames.sanitize("a".repeat(300) + "." + "b".repeat(100), Type.PNG);

		assertThat(result).hasSize(255);
	}
}
