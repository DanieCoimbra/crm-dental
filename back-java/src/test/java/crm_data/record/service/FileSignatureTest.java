package crm_data.record.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import crm_data.record.service.FileSignature.Type;

class FileSignatureTest {

	private static byte[] bytes(int... values) {
		byte[] out = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			out[i] = (byte) values[i];
		}
		return out;
	}

	private static byte[] pad(byte[] head, int length) {
		return Arrays.copyOf(head, length);
	}

	@Test
	void detectsJpeg() {
		assertThat(FileSignature.detect(pad(bytes(0xFF, 0xD8, 0xFF, 0xE0), 12))).contains(Type.JPEG);
		assertThat(FileSignature.detect(bytes(0xFF, 0xD8, 0xFF))).contains(Type.JPEG);
	}

	@Test
	void detectsPng() {
		assertThat(FileSignature.detect(pad(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), 12))).contains(Type.PNG);
	}

	@Test
	void detectsWebpOnlyWithBothRiffAndWebpMarkers() {
		byte[] webp = "RIFF\u0000\u0000\u0000\u0000WEBP".getBytes(StandardCharsets.ISO_8859_1);
		byte[] wave = "RIFF\u0000\u0000\u0000\u0000WAVE".getBytes(StandardCharsets.ISO_8859_1);

		assertThat(FileSignature.detect(webp)).contains(Type.WEBP);
		assertThat(FileSignature.detect(wave)).isEmpty();
		assertThat(FileSignature.detect("RIFF".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
	}

	@Test
	void detectsPdf() {
		assertThat(FileSignature.detect("%PDF-1.7\n".getBytes(StandardCharsets.ISO_8859_1))).contains(Type.PDF);
		assertThat(FileSignature.detect("%PDF".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
	}

	@Test
	void rejectsEverythingElse() {
		assertThat(FileSignature.detect(null)).isEmpty();
		assertThat(FileSignature.detect(new byte[0])).isEmpty();
		assertThat(FileSignature.detect(pad(bytes(0xFF, 0xD8), 12))).isEmpty(); // JPEG incompleto
		assertThat(FileSignature.detect(pad(bytes(0x89, 0x50, 0x4E, 0x47), 12))).isEmpty(); // PNG incompleto
		assertThat(FileSignature.detect("GIF89a......".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
		assertThat(FileSignature.detect("<?php echo 1;".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
		assertThat(FileSignature.detect("MZ\u0090\u0000\u0003\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
	}

	@Test
	void typesCarryTheContractContentTypes() {
		assertThat(Type.JPEG.contentType()).isEqualTo("image/jpeg");
		assertThat(Type.PNG.contentType()).isEqualTo("image/png");
		assertThat(Type.WEBP.contentType()).isEqualTo("image/webp");
		assertThat(Type.PDF.contentType()).isEqualTo("application/pdf");
	}
}
