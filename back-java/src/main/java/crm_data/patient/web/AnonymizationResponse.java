package crm_data.patient.web;

import java.time.Instant;

/** Resultado da anonimização: o instante e a situação da limpeza do Storage ({@code COMPLETED} ou {@code PENDING}). */
public record AnonymizationResponse(Instant anonymizedAt, String storageCleanup) {
}
