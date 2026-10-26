package crm_data.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;

/**
 * Armazenamento de arquivos (exames), por trás de duas implementações: {@link SupabaseFileStorage} (produção, bucket
 * privado) e {@link LocalFileStorage} (dev e teste). <strong>Regra:</strong> nunca chamar dentro de uma transação de
 * banco (o pool tem 10 conexões). <strong>Única exceção (Fase 3, H1):</strong> o envio do PDF do orçamento e a nova
 * revisão gravam o objeto dentro da transação, para que o orçamento só fique {@code SENT} com o PDF registrado; isso só
 * vale pelo bean {@code documentStorage} (timeout de 10 s), com semáforo de envios simultâneos, limite por usuário e
 * compensação (apagar o objeto se a transação falhar). Nenhum outro fluxo pode usar essa exceção.
 *
 * <p>A {@code key} segue {@link StorageKeys}: {@code <clinic_id>/<patient_id>/<uuid>} (exames) ou
 * {@code <clinic_id>/<patient_id>/budgets/<uuid>} (PDF de orçamento); as implementações rejeitam qualquer outra. Falhas de infraestrutura (rede, 4xx/5xx do Storage, timeout) viram
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
