package crm_data.shared.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Armazenamento de arquivos (exames, anexos), usado a partir da Fase 1. Sem implementação nesta fase:
 * a Fase 1 entrega disco persistente ou object storage por trás desta interface.
 *
 * <p>A {@code key} sempre começa com {@code <clinic_id>/} (prefixo do tenant) e nunca contém
 * {@code ..} nem separadores absolutos; as implementações devem rejeitar chaves fora desse padrão.
 */
public interface FileStorage {

	void save(String key, InputStream content) throws IOException;

	InputStream load(String key) throws IOException;

	void delete(String key) throws IOException;
}
