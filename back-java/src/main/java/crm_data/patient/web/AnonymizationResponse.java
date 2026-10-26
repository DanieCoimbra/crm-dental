package crm_data.patient.web;

import java.time.Instant;

/**
 * Resultado da anonimização: o instante, a situação da limpeza do Storage ({@code COMPLETED} ou {@code PENDING}) e quantos
 * orçamentos {@code DRAFT}/{@code SENT} o paciente tinha (aviso; não bloqueia).
 */
public record AnonymizationResponse(Instant anonymizedAt, String storageCleanup, int openBudgets) {
}
