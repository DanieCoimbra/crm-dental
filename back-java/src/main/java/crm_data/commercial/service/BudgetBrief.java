package crm_data.commercial.service;

import java.util.UUID;

/** Id, número e paciente de um orçamento, para outros módulos (financeiro). Nunca traz valores nem observações. */
public record BudgetBrief(UUID id, int number, UUID patientId) {
}
