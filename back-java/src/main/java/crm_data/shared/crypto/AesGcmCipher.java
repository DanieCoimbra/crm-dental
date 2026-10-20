package crm_data.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

import crm_data.shared.crypto.CryptoException.Reason;
import lombok.RequiredArgsConstructor;

/**
 * AES-256-GCM de campos de texto. Formato armazenado: {@code base64( versão[1] | IV[12] | cifrado + tag[16] )}.
 * IV aleatório novo a cada cifragem; chave escolhida pela versão gravada no próprio valor. Stateless e seguro
 * para uso concorrente (um {@link Cipher} por operação). Não registra nada em log.
 */
@Component
@RequiredArgsConstructor
public class AesGcmCipher {

	static final String TRANSFORMATION = "AES/GCM/NoPadding";
	static final int IV_BYTES = 12;
	static final int TAG_BITS = 128;
	static final int TAG_BYTES = TAG_BITS / 8;
	private static final int HEADER_BYTES = 1 + IV_BYTES;
	private static final int MIN_STORED_BYTES = HEADER_BYTES + TAG_BYTES;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final KeyRing keyRing;

	/** {@code null} permanece {@code null}; texto vazio é cifrado normalmente. */
	public String encrypt(String plain) {
		if (plain == null) {
			return null;
		}
		byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);

			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, keyRing.active(), new GCMParameterSpec(TAG_BITS, iv));
			byte[] sealed = cipher.doFinal(plainBytes);

			byte[] stored = new byte[HEADER_BYTES + sealed.length];
			stored[0] = (byte) keyRing.activeVersion();
			System.arraycopy(iv, 0, stored, 1, IV_BYTES);
			System.arraycopy(sealed, 0, stored, HEADER_BYTES, sealed.length);
			return Base64.getEncoder().encodeToString(stored);
		}
		catch (GeneralSecurityException ex) {
			throw new CryptoException(Reason.KEY_INVALID, "Falha ao cifrar o campo");
		}
		finally {
			Arrays.fill(plainBytes, (byte) 0);
		}
	}

	/**
	 * @throws CryptoException {@code MALFORMED}, {@code VERSION_UNKNOWN} ou {@code TAMPERED}; nunca devolve texto
	 *         incorreto
	 */
	public String decrypt(String stored) {
		if (stored == null) {
			return null;
		}
		byte[] bytes = decode(stored);
		SecretKey key = keyRing.byVersion(Byte.toUnsignedInt(bytes[0]));
		byte[] plainBytes = null;
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 1, IV_BYTES));
			plainBytes = cipher.doFinal(bytes, HEADER_BYTES, bytes.length - HEADER_BYTES);
			return new String(plainBytes, StandardCharsets.UTF_8);
		}
		catch (AEADBadTagException ex) {
			throw new CryptoException(Reason.TAMPERED, "Valor cifrado adulterado ou chave incorreta");
		}
		catch (GeneralSecurityException ex) {
			throw new CryptoException(Reason.MALFORMED, "Valor cifrado inválido");
		}
		finally {
			if (plainBytes != null) {
				Arrays.fill(plainBytes, (byte) 0);
			}
		}
	}

	/**
	 * Reescreve o valor com a chave ativa (rotação gradual). Se já estiver na versão ativa, devolve o mesmo valor.
	 */
	public String rotate(String stored) {
		if (stored == null) {
			return null;
		}
		if (Byte.toUnsignedInt(decode(stored)[0]) == keyRing.activeVersion()) {
			return stored;
		}
		return encrypt(decrypt(stored));
	}

	private static byte[] decode(String stored) {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(stored);
		}
		catch (IllegalArgumentException ex) {
			throw new CryptoException(Reason.MALFORMED, "Valor cifrado inválido");
		}
		if (bytes.length < MIN_STORED_BYTES) {
			throw new CryptoException(Reason.MALFORMED, "Valor cifrado inválido");
		}
		return bytes;
	}
}
