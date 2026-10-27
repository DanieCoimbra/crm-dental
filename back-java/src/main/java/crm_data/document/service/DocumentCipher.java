package crm_data.document.service;

import org.springframework.stereotype.Component;

import crm_data.shared.crypto.AesGcmCipher;
import lombok.RequiredArgsConstructor;

/**
 * Cifra explícita do texto do documento sobre o {@link AesGcmCipher} (envelope versionado). Não usa o conversor JPA porque
 * o {@code sha256} cobre o texto cifrado: a cifra acontece uma vez, na emissão; a leitura decifra e nunca recifra. Não
 * registra nada em log.
 */
@Component
@RequiredArgsConstructor
public class DocumentCipher {

	private final AesGcmCipher cipher;

	/** Texto vazio é cifrado normalmente (40 caracteres Base64: atende {@code ck_cdoc_content}). */
	public String encrypt(String plain) {
		return cipher.encrypt(plain == null ? "" : plain);
	}

	public String decrypt(String stored) {
		return cipher.decrypt(stored);
	}
}
