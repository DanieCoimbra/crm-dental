package crm_data.commercial.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Projeção de leitura de um orçamento aprovado (sem hidratar a entidade: faturamento e ficha leem centenas de linhas). */
public interface ApprovedRow {

	UUID getId();

	Integer getNumber();

	UUID getPatientId();

	BigDecimal getTotalAmount();

	Instant getDecidedAt();
}
