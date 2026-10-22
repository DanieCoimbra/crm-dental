package crm_data.shared.storage;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Monta e valida a chave do objeto: {@code <clinic_id>/<patient_id>/<uuid>}, três UUIDs canônicos separados por
 * {@code /}. O nome original do arquivo nunca entra na chave, o que também elimina {@code ..}, barras duplicadas e
 * caracteres especiais.
 */
public final class StorageKeys {

	private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final Pattern KEY = Pattern.compile(UUID_REGEX + "/" + UUID_REGEX + "/" + UUID_REGEX);

	private StorageKeys() {
	}

	public static String of(UUID clinicId, UUID patientId, UUID fileId) {
		return clinicId + "/" + patientId + "/" + fileId;
	}

	/** @throws StorageException se a chave não segue o padrão */
	public static String require(String key) {
		if (key == null || !KEY.matcher(key).matches()) {
			throw new StorageException("Chave de armazenamento inválida.");
		}
		return key;
	}

	/** Chave pertence à clínica? (prefixo {@code <clinic_id>/}). */
	public static boolean belongsTo(String key, UUID clinicId) {
		return key != null && key.startsWith(clinicId + "/");
	}
}
