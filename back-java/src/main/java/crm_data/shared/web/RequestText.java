package crm_data.shared.web;

import java.util.Locale;

/** Normalização de texto vindo do cliente, aplicada na construção dos DTOs (antes da validação e de qualquer busca). */
public final class RequestText {

	private RequestText() {
	}

	/** Remove espaços das pontas; {@code null} continua {@code null}. */
	public static String strip(String value) {
		return value == null ? null : value.strip();
	}

	/** Remove espaços das pontas e transforma vazio em {@code null} (campos opcionais). */
	public static String blankToNull(String value) {
		String stripped = strip(value);
		return stripped == null || stripped.isEmpty() ? null : stripped;
	}

	/** E-mail sem espaços nas pontas e em minúsculas (a coluna e o índice único exigem minúsculas). */
	public static String normalizeEmail(String value) {
		return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
	}
}
