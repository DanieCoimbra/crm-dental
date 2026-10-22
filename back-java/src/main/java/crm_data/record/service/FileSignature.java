package crm_data.record.service;

import java.util.Optional;

/**
 * Detecta o tipo real do arquivo pelos bytes iniciais (magic numbers), sem confiar no {@code Content-Type} nem na
 * extensão enviados pelo cliente. Só os quatro tipos aceitos nos exames.
 */
public final class FileSignature {

	/** Bytes que bastam para reconhecer todos os tipos (WebP usa os 12 primeiros). */
	public static final int HEADER_BYTES = 12;

	public enum Type {
		JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp"), PDF("application/pdf", "pdf");

		private final String contentType;
		private final String extension;

		Type(String contentType, String extension) {
			this.contentType = contentType;
			this.extension = extension;
		}

		public String contentType() {
			return contentType;
		}

		public String extension() {
			return extension;
		}
	}

	private FileSignature() {
	}

	public static Optional<Type> detect(byte[] head) {
		if (head == null) {
			return Optional.empty();
		}
		if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
			return Optional.of(Type.JPEG);
		}
		if (startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
			return Optional.of(Type.PNG);
		}
		if (head.length >= 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) {
			return Optional.of(Type.WEBP);
		}
		if (ascii(head, 0, "%PDF-")) {
			return Optional.of(Type.PDF);
		}
		return Optional.empty();
	}

	private static boolean startsWith(byte[] head, int... expected) {
		if (head.length < expected.length) {
			return false;
		}
		for (int i = 0; i < expected.length; i++) {
			if ((head[i] & 0xFF) != expected[i]) {
				return false;
			}
		}
		return true;
	}

	private static boolean ascii(byte[] head, int offset, String text) {
		if (head.length < offset + text.length()) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			if (head[offset + i] != (byte) text.charAt(i)) {
				return false;
			}
		}
		return true;
	}
}
