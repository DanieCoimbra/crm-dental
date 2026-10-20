package crm_data.clinic.service;

import java.util.UUID;

/** Eventos internos, em memória, do módulo de clínica e usuários (somente identificadores). */
public final class ClinicEvents {

	private ClinicEvents() {
	}

	/** {@code Clinic.Updated}: dados da clínica editados. */
	public record Updated(UUID clinicId) {
	}

	/** {@code User.Created}: recepcionista cadastrado. */
	public record UserCreated(UUID userId, UUID clinicId) {
	}
}
