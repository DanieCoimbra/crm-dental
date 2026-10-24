package crm_data.patient.service;

import java.util.UUID;

/**
 * Resumo do paciente para outros módulos (a agenda mostra nome e telefone). Nunca vai para log: o {@code toString}
 * traz só o id.
 */
public record PatientBrief(UUID id, String name, String phone) {

	@Override
	public String toString() {
		return "PatientBrief[id=" + id + "]";
	}
}
