package crm_data.catalog.service;

import java.util.UUID;

/** Eventos internos, em memória, do catálogo. Só identificadores e a situação: nunca nome nem preço. */
public final class CatalogEvents {

	private CatalogEvents() {
	}

	/** {@code ProcedureType.Created}: procedimento criado (publicado depois do commit). */
	public record Created(UUID clinicId, UUID procedureTypeId, UUID userId) {
	}

	/** {@code ProcedureType.Updated}: nome, preço, duração ou cor alterados. */
	public record Updated(UUID clinicId, UUID procedureTypeId, UUID userId) {
	}

	/** {@code ProcedureType.ActiveChanged}: procedimento ativado ou inativado. */
	public record ActiveChanged(UUID clinicId, UUID procedureTypeId, boolean active, UUID userId) {
	}
}
