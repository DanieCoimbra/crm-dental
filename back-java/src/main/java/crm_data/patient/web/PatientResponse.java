package crm_data.patient.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import crm_data.patient.domain.Patient;

/** Ficha do paciente. Nunca traz {@code clinicId}, {@code createdBy} nem qualquer campo de prontuário. */
public record PatientResponse(UUID id, String name, String cpf, LocalDate birthDate, String sex, String profession,
		String phone, String email, String address, String insuranceName, String insuranceNumber,
		String acquaintanceName, String acquaintancePhone, boolean active, Instant deactivatedAt, Instant anonymizedAt,
		Instant createdAt, Instant updatedAt, Long version) {

	public static PatientResponse from(Patient p) {
		return new PatientResponse(p.getId(), p.getName(), p.getCpf(), p.getBirthDate(), p.getSex().name(),
				p.getProfession(), p.getPhone(), p.getEmail(), p.getAddress(), p.getInsuranceName(),
				p.getInsuranceNumber(), p.getAcquaintanceName(), p.getAcquaintancePhone(), p.isActive(),
				p.getDeactivatedAt(), p.getAnonymizedAt(), p.getCreatedAt(), p.getUpdatedAt(), p.getVersion());
	}

	/** Nunca imprime dados pessoais. */
	@Override
	public String toString() {
		return "PatientResponse[id=" + id + "]";
	}
}
