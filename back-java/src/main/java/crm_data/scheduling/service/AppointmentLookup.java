package crm_data.scheduling.service;

import java.util.UUID;

/**
 * Interface pública do módulo {@code scheduling} para {@code record}: o vínculo da nota clínica com o atendimento
 * precisa checar que o agendamento existe na clínica e é do mesmo paciente, sem expor repositório nem entidade.
 */
public interface AppointmentLookup {

	/**
	 * @throws crm_data.shared.error.BusinessException {@code not-found} (404) se o agendamento não existe na clínica;
	 *         {@code conflict} (409) se é de outro paciente
	 */
	void requireOfPatient(UUID appointmentId, UUID patientId);

	/**
	 * Como {@link #requireOfPatient}, mas trava a linha do agendamento até o fim da transação. Serializa duas
	 * gravações de nota para o mesmo atendimento: a segunda só confere "já existe nota?" depois do commit da primeira.
	 */
	void lockOfPatient(UUID appointmentId, UUID patientId);
}
