package crm_data.patient.service;

import java.util.Collection;
import java.util.UUID;

/**
 * Ponto de extensão da anonimização do paciente: outros módulos (por exemplo {@code commercial}, que guarda PDFs no
 * Storage) dizem quais objetos pertencem ao paciente e quantos itens em aberto ele ainda tem, sem que {@code patient}
 * dependa deles (isso criaria ciclo, pois {@code commercial} usa {@link PatientLookup}). A interface é definida aqui e
 * implementada pelo módulo dono do dado; o {@link PatientAnonymizationService} consulta todos os beans.
 *
 * <p>Chamado dentro da transação da fase de banco, <em>depois</em> da função {@code core.anonymize_patient}.
 * Nenhum método deve registrar chave de objeto em log.
 */
public interface AnonymizationContributor {

	/** {@code storage_key} de todos os objetos do paciente neste módulo, inclusive os já marcados como removidos. */
	Collection<String> objectKeys(UUID patientId);

	/** Quantos itens em aberto o paciente tem neste módulo (aviso na resposta; não bloqueia). */
	default int openItems(UUID patientId) {
		return 0;
	}
}
