package crm_data.commercial.service;

import java.util.Collection;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import crm_data.commercial.repository.BudgetDocumentRepository;
import crm_data.commercial.repository.BudgetRepository;
import crm_data.patient.service.AnonymizationContributor;
import lombok.RequiredArgsConstructor;

/**
 * Parte do módulo comercial na anonimização do paciente: diz quais PDFs apagar do Storage e quantos orçamentos
 * {@code DRAFT}/{@code SENT} ele ainda tem. Implementa a SPI de {@code patient} (que nunca importa este módulo). Roda
 * <strong>dentro</strong> da transação do chamador, depois de {@code core.anonymize_patient}: as linhas de documento já
 * estão com {@code deleted_at}, e as chaves delas também precisam sair do Storage. Nunca registra chave em log.
 */
@Component
@RequiredArgsConstructor
class BudgetAnonymizationContributor implements AnonymizationContributor {

	private final BudgetDocumentRepository documents;
	private final BudgetRepository budgets;

	@Override
	@Transactional(readOnly = true)
	public Collection<String> objectKeys(UUID patientId) {
		return documents.findStorageKeysByPatientId(patientId);
	}

	@Override
	@Transactional(readOnly = true)
	public int openItems(UUID patientId) {
		return (int) budgets.countOpenByPatientId(patientId);
	}
}
