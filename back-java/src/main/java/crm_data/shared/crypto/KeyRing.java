package crm_data.shared.crypto;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import crm_data.shared.crypto.CryptoException.Reason;

/**
 * Chaveiro imutável: versão (1–255) → chave AES-256, mais a versão ativa (a que cifra dados novos). As versões
 * antigas permanecem para leitura. As mensagens de erro citam a versão, nunca o valor da chave.
 */
public final class KeyRing {

	static final int KEY_BYTES = 32;
	private static final int MIN_VERSION = 1;
	private static final int MAX_VERSION = 255;

	private final Map<Integer, SecretKey> keys;
	private final int activeVersion;

	private KeyRing(Map<Integer, SecretKey> keys, int activeVersion) {
		this.keys = Collections.unmodifiableMap(keys);
		this.activeVersion = activeVersion;
	}

	/**
	 * @param keysSpec {@code <versão>:<base64 de 32 bytes>[,<versão>:<base64>...]} ({@code CRM_AES_KEYS})
	 * @param activeVersionSpec inteiro presente no chaveiro ({@code CRM_AES_ACTIVE_VERSION})
	 * @throws CryptoException {@link Reason#KEY_INVALID} se algo estiver ausente ou inválido
	 */
	public static KeyRing parse(String keysSpec, String activeVersionSpec) {
		if (keysSpec == null || keysSpec.isBlank()) {
			throw invalid("CRM_AES_KEYS ausente");
		}
		if (activeVersionSpec == null || activeVersionSpec.isBlank()) {
			throw invalid("CRM_AES_ACTIVE_VERSION ausente");
		}

		Map<Integer, SecretKey> parsed = new LinkedHashMap<>();
		for (String entry : keysSpec.split(",", -1)) {
			int separator = entry.indexOf(':');
			if (separator < 0) {
				throw invalid("CRM_AES_KEYS: cada item deve ter o formato <versão>:<base64>");
			}
			int version = parseVersion(entry.substring(0, separator).trim(), "CRM_AES_KEYS");
			if (parsed.containsKey(version)) {
				throw invalid("CRM_AES_KEYS: versão " + version + " repetida");
			}
			parsed.put(version, decodeKey(version, entry.substring(separator + 1).trim()));
		}

		int active = parseVersion(activeVersionSpec.trim(), "CRM_AES_ACTIVE_VERSION");
		if (!parsed.containsKey(active)) {
			throw invalid("CRM_AES_ACTIVE_VERSION: a versão " + active + " não existe em CRM_AES_KEYS");
		}
		return new KeyRing(parsed, active);
	}

	public int activeVersion() {
		return activeVersion;
	}

	public SecretKey active() {
		return keys.get(activeVersion);
	}

	/** @throws CryptoException {@link Reason#VERSION_UNKNOWN} se o chaveiro não tem essa versão */
	public SecretKey byVersion(int version) {
		SecretKey key = keys.get(version);
		if (key == null) {
			throw new CryptoException(Reason.VERSION_UNKNOWN, "Versão de chave desconhecida: " + version);
		}
		return key;
	}

	/** Só versões; nunca o material das chaves. */
	@Override
	public String toString() {
		return "KeyRing[versions=" + new TreeSet<>(keys.keySet()) + ", active=" + activeVersion + "]";
	}

	private static int parseVersion(String text, String variable) {
		int version;
		try {
			version = Integer.parseInt(text);
		}
		catch (NumberFormatException ex) {
			throw invalid(variable + ": a versão deve ser um inteiro entre " + MIN_VERSION + " e " + MAX_VERSION);
		}
		if (version < MIN_VERSION || version > MAX_VERSION) {
			throw invalid(variable + ": a versão deve ser um inteiro entre " + MIN_VERSION + " e " + MAX_VERSION);
		}
		return version;
	}

	private static SecretKey decodeKey(int version, String base64) {
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(base64);
		}
		catch (IllegalArgumentException ex) {
			// sem encadear a causa: a mensagem do JDK cita caracteres do valor
			throw invalid("CRM_AES_KEYS: a chave da versão " + version + " não é base64 válido");
		}
		if (raw.length != KEY_BYTES) {
			throw invalid("CRM_AES_KEYS: a chave da versão " + version + " deve ter " + KEY_BYTES + " bytes");
		}
		return new SecretKeySpec(raw, "AES");
	}

	private static CryptoException invalid(String message) {
		return new CryptoException(Reason.KEY_INVALID, message);
	}
}
