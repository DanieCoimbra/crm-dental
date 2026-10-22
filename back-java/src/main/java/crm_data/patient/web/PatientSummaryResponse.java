package crm_data.patient.web;

import java.time.LocalDate;
import java.util.UUID;

import crm_data.patient.domain.Patient;

/** Item da listagem: só o necessário para escolher o paciente. */
public record PatientSummaryResponse(UUID id, String name, String phone, LocalDate birthDate, String insuranceName,
		boolean active) {

	public static PatientSummaryResponse from(Patient p) {
		return new PatientSummaryResponse(p.getId(), p.getName(), p.getPhone(), p.getBirthDate(), p.getInsuranceName(),
				p.isActive());
	}

	/** Nunca imprime dados pessoais. */
	@Override
	public String toString() {
		return "PatientSummaryResponse[id=" + id + "]";
	}
}
