package crm_data.shared.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Carrega o chaveiro de {@code CRM_AES_KEYS} e {@code CRM_AES_ACTIVE_VERSION}. Como o {@link KeyRing} é um
 * singleton criado no startup, chave ausente, com tamanho diferente de 32 bytes, base64 inválido ou versão ativa
 * inexistente impedem a aplicação de subir (em qualquer perfil), sem imprimir a chave. Não há valores padrão.
 */
@Configuration(proxyBeanMethods = false)
public class CryptoConfig {

	@Bean
	KeyRing keyRing(@Value("${crm.crypto.keys:}") String keys,
			@Value("${crm.crypto.active-version:}") String activeVersion) {
		return KeyRing.parse(keys, activeVersion);
	}
}
