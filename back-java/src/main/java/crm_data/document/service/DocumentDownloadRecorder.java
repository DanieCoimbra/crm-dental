package crm_data.document.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.DatabaseErrors;
import crm_data.shared.error.ProblemTypes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;

/**
 * Grava o evento {@code DOWNLOAD} chamando {@code core.record_document_download(uuid)} (função {@code SECURITY DEFINER} que
 * confere papel, clínica e estado e insere na trilha). Roda na transação do chamador: se o PDF falhar depois, a linha some
 * junto. O backend nunca insere em {@code audit_log} diretamente.
 */
@Component
@RequiredArgsConstructor
public class DocumentDownloadRecorder {

	private final EntityManager entityManager;

	/**
	 * @throws BusinessException {@code not-found} (404) se o documento sumiu ({@code P0002}); {@code conflict} (409) se o
	 *         paciente foi anonimizado ({@code CR003}); outros erros do banco seguem para o tratamento global (500)
	 */
	public void record(UUID documentId) {
		try {
			// a função devolve void: o cast para text só serve para ler o resultado como consulta
			entityManager.createNativeQuery("select cast(core.record_document_download(:id) as text)")
					.setParameter("id", documentId).getSingleResult();
		}
		catch (PersistenceException ex) {
			String state = DatabaseErrors.sqlState(ex).orElse("");
			if (state.equals("P0002")) {
				throw new BusinessException(ProblemTypes.NOT_FOUND, "Documento não encontrado.");
			}
			if (state.equals("CR003")) {
				throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.DOCUMENT_ANONYMIZED);
			}
			throw ex;
		}
	}
}
