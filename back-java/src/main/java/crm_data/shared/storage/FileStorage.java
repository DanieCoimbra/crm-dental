package crm_data.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;

/**
 * Armazenamento de arquivos (exames), por trás de duas implementações: {@link SupabaseFileStorage} (produção, bucket
 * privado) e {@link LocalFileStorage} (dev e teste). Nunca deve ser chamado dentro de uma transação de banco.
 *
 * <p>A {@code key} é sempre {@code <clinic_id>/<patient_id>/<uuid>} (ver {@link StorageKeys}); as implementações
 * rejeitam qualquer chave fora desse padrão. Falhas de infraestrutura (rede, 4xx/5xx do Storage, timeout) viram
 * {@link StorageException}, cuja mensagem nunca traz URL, chave nem nome de arquivo.
 */
public interface FileStorage {

	/** Grava o conteúdo em streaming. O Supabase exige o tipo e o tamanho. Não sobrescreve objeto existente. */
	void save(String key, InputStream content, String contentType, long size) throws IOException;

	/** Sem uso nesta fase: o download é por URL assinada. */
	InputStream load(String key) throws IOException;

	/** Remove um objeto; objeto inexistente não é erro. */
	void delete(String key) throws IOException;

	/** Remove vários objetos (lote). Por padrão, um {@link #delete} por chave. */
	default void deleteAll(Collection<String> keys) throws IOException {
		for (String key : keys) {
			delete(key);
		}
	}

	/** URL temporária de leitura. Nunca registrar a URL em log. */
	URI createSignedUrl(String key, Duration ttl) throws IOException;
}
