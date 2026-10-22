package crm_data.shared.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.LocalFileStorage;
import crm_data.shared.storage.SupabaseFileStorage;

/**
 * Escolhe a implementação do {@link FileStorage}: Supabase quando {@code crm.storage.url} está preenchida (produção),
 * disco local caso contrário (dev e teste). Em {@code prod} o {@link StartupConfigValidator} exige as três variáveis
 * {@code CRM_STORAGE_*}, então o disco local nunca é usado lá.
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

	@Bean
	FileStorage fileStorage(CrmProperties properties) {
		CrmProperties.Storage storage = properties.storage();
		if (storage != null && StringUtils.hasText(storage.url())) {
			return new SupabaseFileStorage(storage.url(), storage.serviceKey(), storage.bucket(), CONNECT_TIMEOUT,
					READ_TIMEOUT);
		}
		return new LocalFileStorage(localRoot(storage));
	}

	private static Path localRoot(CrmProperties.Storage storage) {
		try {
			if (storage != null && StringUtils.hasText(storage.localDir())) {
				return Files.createDirectories(Path.of(storage.localDir()));
			}
			return Files.createTempDirectory("crm-files-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Não foi possível preparar o diretório local de arquivos.", ex);
		}
	}
}
