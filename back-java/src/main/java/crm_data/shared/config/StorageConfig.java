package crm_data.shared.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.LocalFileStorage;
import crm_data.shared.storage.SupabaseFileStorage;

/**
 * Escolhe a implementação do {@link FileStorage}: Supabase quando {@code crm.storage.url} está preenchida (produção),
 * disco local caso contrário (dev e teste). Em {@code prod} o {@link StartupConfigValidator} exige as três variáveis
 * {@code CRM_STORAGE_*}, então o disco local nunca é usado lá.
 *
 * <p>Há dois beans: {@code fileStorage} (exames, leitura de 30 s, {@link Primary}) e {@code documentStorage} (PDF do
 * orçamento: mesmo bucket e mesma chave de serviço, mas com leitura de {@code crm.documents.upload-timeout}, 10 s,
 * porque o envio do PDF segura uma conexão do pool). Em dev e teste ambos usam o <em>mesmo</em> diretório local.
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

	/** Nome do qualificador do Storage dos PDFs de orçamento. */
	public static final String DOCUMENT_STORAGE = "documentStorage";

	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

	@Bean
	@Primary
	FileStorage fileStorage(CrmProperties properties) {
		return build(properties, READ_TIMEOUT);
	}

	@Bean(DOCUMENT_STORAGE)
	@Qualifier(DOCUMENT_STORAGE)
	FileStorage documentStorage(CrmProperties properties) {
		return build(properties, properties.documents().uploadTimeout());
	}

	private static FileStorage build(CrmProperties properties, Duration readTimeout) {
		CrmProperties.Storage storage = properties.storage();
		if (storage != null && StringUtils.hasText(storage.url())) {
			return new SupabaseFileStorage(storage.url(), storage.serviceKey(), storage.bucket(), CONNECT_TIMEOUT,
					readTimeout);
		}
		return new LocalFileStorage(localRoot(storage));
	}

	/**
	 * Diretório local compartilhado pelos dois beans: sem {@code local-dir}, um diretório temporário <strong>por
	 * processo</strong> (criado uma vez), para que um objeto gravado por um bean seja visível ao outro.
	 */
	private static Path localRoot(CrmProperties.Storage storage) {
		try {
			if (storage != null && StringUtils.hasText(storage.localDir())) {
				return Files.createDirectories(Path.of(storage.localDir()));
			}
			return TempRoot.INSTANCE;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Não foi possível preparar o diretório local de arquivos.", ex);
		}
	}

	/** Diretório temporário criado uma única vez por JVM. */
	private static final class TempRoot {

		static final Path INSTANCE;

		static {
			try {
				INSTANCE = Files.createTempDirectory("crm-files-");
			}
			catch (IOException ex) {
				throw new UncheckedIOException("Não foi possível preparar o diretório local de arquivos.", ex);
			}
		}
	}
}
