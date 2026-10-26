package crm_data.commercial.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Orçamento aprovado, como o financeiro precisa dele: identificação, paciente, total e instante da decisão. Nunca traz
 * observação nem itens. O {@code toString} não imprime valor.
 */
public record ApprovedBudget(UUID id, int number, UUID patientId, BigDecimal totalAmount, Instant decidedAt) {

	@Override
	public String toString() {
		return "ApprovedBudget[id=" + id + ", number=" + number + "]";
	}
}
