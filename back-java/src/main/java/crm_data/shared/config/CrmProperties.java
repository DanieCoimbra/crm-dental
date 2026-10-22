package crm_data.shared.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades próprias da aplicação (prefixo {@code crm}), alimentadas por variáveis de ambiente.
 *
 * @param corsOrigins origens permitidas pelo CORS (lista de {@code CRM_CORS_ORIGINS}); nunca {@code *}
 * @param storage Supabase Storage dos arquivos de exame (Fase 1)
 * @param hstsEnabled liga o {@code Strict-Transport-Security} (perfil prod)
 */
@ConfigurationProperties(prefix = "crm")
public record CrmProperties(List<String> corsOrigins, Storage storage, boolean hstsEnabled) {

	public CrmProperties {
		corsOrigins = corsOrigins == null ? List.of()
				: corsOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
	}

	/**
	 * @param url URL do projeto Supabase ({@code CRM_STORAGE_URL}); vazia em dev/teste, quando o disco local é usado
	 * @param serviceKey service key, só no backend ({@code CRM_STORAGE_SERVICE_KEY})
	 * @param bucket bucket privado dos exames ({@code CRM_STORAGE_BUCKET})
	 * @param signedUrlTtl validade da URL assinada de download (padrão 60 s)
	 * @param localDir diretório do {@code LocalFileStorage} (só dev/teste); vazio usa um diretório temporário
	 */
	public record Storage(String url, String serviceKey, String bucket, Duration signedUrlTtl, String localDir) {

		public Storage {
			signedUrlTtl = signedUrlTtl == null ? Duration.ofSeconds(60) : signedUrlTtl;
		}
	}
}
