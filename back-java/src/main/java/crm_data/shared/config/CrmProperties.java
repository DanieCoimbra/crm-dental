package crm_data.shared.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Propriedades próprias da aplicação (prefixo {@code crm}), alimentadas por variáveis de ambiente.
 *
 * @param corsOrigins origens permitidas pelo CORS (lista de {@code CRM_CORS_ORIGINS}); nunca {@code *}
 * @param storage Supabase Storage dos arquivos de exame (Fase 1)
 * @param hstsEnabled liga o {@code Strict-Transport-Security} (perfil prod)
 * @param documents limites do envio do PDF do orçamento (Fase 3); todos com padrão
 */
@ConfigurationProperties(prefix = "crm")
public record CrmProperties(List<String> corsOrigins, Storage storage, boolean hstsEnabled, Documents documents,
		ClinicalDocuments clinicalDocuments) {

	/** Forma anterior à Fase 4 (sem os limites dos documentos clínicos, que usam o padrão). */
	public CrmProperties(List<String> corsOrigins, Storage storage, boolean hstsEnabled, Documents documents) {
		this(corsOrigins, storage, hstsEnabled, documents, null);
	}

	@ConstructorBinding
	public CrmProperties {
		corsOrigins = corsOrigins == null ? List.of()
				: corsOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
		documents = documents == null ? Documents.defaults() : documents;
		clinicalDocuments = clinicalDocuments == null ? ClinicalDocuments.defaults() : clinicalDocuments;
	}

	/**
	 * Limites por usuário dos documentos clínicos (Fase 4), por minuto: emissões e PDFs (padrão 30 cada; 1 a 600, validado
	 * na inicialização do perfil prod).
	 */
	public record ClinicalDocuments(Integer issuesPerMinute, Integer pdfsPerMinute) {

		public static final int DEFAULT_PER_MINUTE = 30;

		public ClinicalDocuments {
			issuesPerMinute = issuesPerMinute == null ? DEFAULT_PER_MINUTE : issuesPerMinute;
			pdfsPerMinute = pdfsPerMinute == null ? DEFAULT_PER_MINUTE : pdfsPerMinute;
		}

		static ClinicalDocuments defaults() {
			return new ClinicalDocuments(null, null);
		}
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

	/**
	 * Envio do PDF do orçamento: grava no Storage <em>dentro</em> da transação (exceção documentada à regra dos exames),
	 * então os limites protegem o pool de 10 conexões.
	 *
	 * @param uploadTimeout leitura do Storage no envio do PDF (padrão 10 s)
	 * @param maxConcurrent envios com PDF simultâneos por instância (padrão 3)
	 * @param sendsPerMinute envios ou revisões por minuto por usuário (padrão 6)
	 * @param transactionTimeout teto da transação do envio (padrão 20 s)
	 */
	public record Documents(Duration uploadTimeout, Integer maxConcurrent, Integer sendsPerMinute,
			Duration transactionTimeout) {

		public Documents {
			uploadTimeout = uploadTimeout == null ? Duration.ofSeconds(10) : uploadTimeout;
			maxConcurrent = maxConcurrent == null || maxConcurrent < 1 ? 3 : maxConcurrent;
			sendsPerMinute = sendsPerMinute == null || sendsPerMinute < 1 ? 6 : sendsPerMinute;
			transactionTimeout = transactionTimeout == null ? Duration.ofSeconds(20) : transactionTimeout;
		}

		static Documents defaults() {
			return new Documents(null, null, null, null);
		}
	}
}
