package crm_data.shared.crypto;

import org.springframework.stereotype.Component;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;

/**
 * Cifra o campo ao gravar e decifra ao ler, de forma transparente à entidade. Uso:
 * {@code @Convert(converter = EncryptedStringConverter.class)} sobre um {@code String}, com coluna {@code text}.
 * O valor cifrado não é pesquisável: nunca use o campo em {@code WHERE}, {@code ORDER BY} ou índice.
 */
@Converter
@Component
@RequiredArgsConstructor
public class EncryptedStringConverter implements AttributeConverter<String, String> {

	private final AesGcmCipher cipher;

	@Override
	public String convertToDatabaseColumn(String attribute) {
		return cipher.encrypt(attribute);
	}

	@Override
	public String convertToEntityAttribute(String dbData) {
		return cipher.decrypt(dbData);
	}
}
