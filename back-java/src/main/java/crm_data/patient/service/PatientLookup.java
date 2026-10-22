package crm_data.patient.service;

import java.util.UUID;

/**
 * Interface pública do módulo {@code patient} para {@code record} (F03, F04, F05): permite checar o paciente sem
 * expor repositório nem entidade.
 */
public interface PatientLookup {

	/** @throws crm_data.shared.error.BusinessException {@code not-found} (404) se o paciente não existe na clínica */
	void requireExists(UUID patientId);

	/**
	 * @throws crm_data.shared.error.BusinessException {@code not-found} (404) se não existe; {@code conflict} (409)
	 *         se o paciente está anonimizado
	 */
	void requireWritable(UUID patientId);
}
