package crm_data.catalog.service;

import java.util.UUID;

/** Resumo do procedimento para a agenda (nome e cor do rótulo). */
public record ProcedureBrief(UUID id, String name, String color) {
}
