package crm_data.record.service;

import java.text.Normalizer;

/**
 * Sanitiza o nome original do arquivo para guardá-lo como metadado (ele nunca entra na chave do Storage): só o último
 * segmento do caminho, sem caracteres de controle, em NFC, sem espaços nas pontas e com até 255 caracteres (mantendo a
 * extensão ao truncar). Nome vazio ou só de pontos vira {@code arquivo.<ext do tipo detectado>}.
 */
public final class FileNames {

	static final int MAX_LENGTH = 255;
	private static final int MAX_EXTENSION = 20;

	private FileNames() {
	}

	public static String sanitize(String original, FileSignature.Type type) {
		String name = original == null ? "" : original;
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		name = slash >= 0 ? name.substring(slash + 1) : name;
		name = name.replaceAll("[\\p{Cc}\\p{Cf}]", "");
		name = Normalizer.normalize(name, Normalizer.Form.NFC).strip();
		if (name.isEmpty() || name.chars().allMatch(c -> c == '.')) {
			return "arquivo." + type.extension();
		}
		return name.length() <= MAX_LENGTH ? name : truncate(name);
	}

	private static String truncate(String name) {
		int dot = name.lastIndexOf('.');
		String extension = dot > 0 && name.length() - dot <= MAX_EXTENSION ? name.substring(dot) : "";
		int keep = MAX_LENGTH - extension.length();
		if (Character.isLowSurrogate(name.charAt(keep)) && Character.isHighSurrogate(name.charAt(keep - 1))) {
			keep--; // não parte um par substituto ao meio
		}
		return name.substring(0, keep) + extension;
	}
}
