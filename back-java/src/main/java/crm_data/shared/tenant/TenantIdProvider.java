package crm_data.shared.tenant;

import java.util.UUID;

/** Único ponto que entrega o {@code clinic_id} às entidades: sempre o do {@link TenantContext}. */
public final class TenantIdProvider {

	private TenantIdProvider() {
	}

	public static UUID currentClinicId() {
		return TenantContext.clinicId();
	}
}
