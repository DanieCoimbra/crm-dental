package crm_data.catalog.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Nome e preço-base para montar o orçamento. O preço nunca vai para log: o {@code toString} traz só o id.
 */
public record ProcedureQuote(UUID id, String name, BigDecimal basePrice, boolean active) {

	@Override
	public String toString() {
		return "ProcedureQuote[id=" + id + "]";
	}
}
