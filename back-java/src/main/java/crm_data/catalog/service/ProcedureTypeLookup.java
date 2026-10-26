package crm_data.catalog.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Interface pública do módulo {@code catalog} para {@code scheduling} (procedimento do agendamento) e
 * {@code commercial} (itens do orçamento): permite checar e resumir procedimentos sem expor repositório nem entidade.
 * Cada método usa <strong>uma</strong> consulta, qualquer que seja o número de ids.
 */
public interface ProcedureTypeLookup {

	/**
	 * @throws crm_data.shared.error.BusinessException {@code not-found} (404) se o procedimento não existe na clínica;
	 *         {@code business-rule} (422, {@code reason = PROCEDURE_INACTIVE}) se está inativo
	 */
	void requireActive(UUID procedureTypeId);

	/** Id, nome e cor em lote. Id desconhecido ou de outra clínica simplesmente não aparece no mapa. */
	Map<UUID, ProcedureBrief> briefs(Collection<UUID> ids);

	/** Nome, preço-base e situação em lote (para o orçamento). Id desconhecido ou de outra clínica não aparece. */
	Map<UUID, ProcedureQuote> quotes(Collection<UUID> ids);
}
