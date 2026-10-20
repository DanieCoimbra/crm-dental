package crm_data.shared.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades próprias da aplicação (prefixo {@code crm}), alimentadas por variáveis de ambiente.
 *
 * @param corsOrigins origens permitidas pelo CORS (lista de {@code CRM_CORS_ORIGINS}); nunca {@code *}
 * @param filesDir pasta de arquivos (Fase 1)
 * @param hstsEnabled liga o {@code Strict-Transport-Security} (perfil prod)
 */
@ConfigurationProperties(prefix = "crm")
public record CrmProperties(List<String> corsOrigins, String filesDir, boolean hstsEnabled) {

	public CrmProperties {
		corsOrigins = corsOrigins == null ? List.of()
				: corsOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
	}
}
