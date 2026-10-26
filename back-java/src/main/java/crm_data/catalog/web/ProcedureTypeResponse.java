package crm_data.catalog.web;

import java.util.List;
import java.util.UUID;

import crm_data.catalog.domain.ProcedureType;
import crm_data.shared.money.Money;

/** Procedimento na API. O preço é texto decimal; nunca traz {@code clinicId} nem {@code createdBy}. */
public record ProcedureTypeResponse(UUID id, String name, String basePrice, int defaultDurationMinutes, String color,
		boolean active, Long version) {

	public static ProcedureTypeResponse from(ProcedureType procedure) {
		return new ProcedureTypeResponse(procedure.getId(), procedure.getName(), Money.format(procedure.getBasePrice()),
				procedure.getDefaultDurationMinutes(), procedure.getColor(), procedure.isActive(), procedure.getVersion());
	}

	public static List<ProcedureTypeResponse> from(List<ProcedureType> procedures) {
		return procedures.stream().map(ProcedureTypeResponse::from).toList();
	}
}
