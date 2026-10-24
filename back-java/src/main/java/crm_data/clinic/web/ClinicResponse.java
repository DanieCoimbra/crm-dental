package crm_data.clinic.web;

import java.util.UUID;

import crm_data.auth.domain.Clinic;

/** Clínica na API (contrato da F05, §3), com a {@code version} para o controle de edição concorrente. */
public record ClinicResponse(UUID id, String name, String document, String phone, String email, String address,
		String timezone, Long version) {

	public static ClinicResponse from(Clinic clinic) {
		return new ClinicResponse(clinic.getId(), clinic.getName(), clinic.getDocument(), clinic.getPhone(),
				clinic.getEmail(), clinic.getAddress(), clinic.getTimezone(), clinic.getVersion());
	}
}
