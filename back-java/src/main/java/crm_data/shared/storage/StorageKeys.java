package crm_data.shared.storage;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Monta e valida a chave do objeto. Há duas formas, ambas feitas só de UUIDs canônicos (minúsculos) e do literal
 * {@code budgets}, separados por {@code /}:
 * <ul>
 * <li>exame: {@code <clinic_id>/<patient_id>/<uuid>};</li>
 * <li>PDF de orçamento: {@code <clinic_id>/<patient_id>/budgets/<uuid>} (118 caracteres; cabe em {@code varchar(120)}).</li>
 * </ul>
 * O nome original do arquivo nunca entra na chave, o que também elimina {@code ..}, barras duplicadas e caracteres
 * especiais. As expressões são fechadas: nenhum texto do cliente chega à chave.
 */
public final class StorageKeys {

	private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final Pattern FILE_KEY = Pattern.compile(UUID_REGEX + "/" + UUID_REGEX + "/" + UUID_REGEX);
	private static final Pattern BUDGET_KEY = Pattern
			.compile(UUID_REGEX + "/" + UUID_REGEX + "/budgets/" + UUID_REGEX);

	private StorageKeys() {
	}

	public static String of(UUID clinicId, UUID patientId, UUID fileId) {
		return clinicId + "/" + patientId + "/" + fileId;
	}

	/** Chave do PDF de um orçamento: {@code <clinic_id>/<patient_id>/budgets/<uuid>}. */
	public static String ofBudget(UUID clinicId, UUID patientId, UUID objectId) {
		return clinicId + "/" + patientId + "/budgets/" + objectId;
	}

	/** @throws StorageException se a chave não segue nenhuma das duas formas */
	public static String require(String key) {
		if (key == null || !(FILE_KEY.matcher(key).matches() || BUDGET_KEY.matcher(key).matches())) {
			throw new StorageException("Chave de armazenamento inválida.");
		}
		return key;
	}

	/** Chave pertence à clínica? (prefixo {@code <clinic_id>/}). */
	public static boolean belongsTo(String key, UUID clinicId) {
		return key != null && key.startsWith(clinicId + "/");
	}
}
