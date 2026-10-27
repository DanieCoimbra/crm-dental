package crm_data.catalog.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.catalog.domain.ProcedureType;
import crm_data.catalog.repository.ProcedureTypeRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class ProcedureTypeLookupService implements ProcedureTypeLookup {

	static final String INACTIVE_REASON = "PROCEDURE_INACTIVE";

	private final ProcedureTypeRepository procedureTypes;

	@Override
	@Transactional(readOnly = true)
	public void requireActive(UUID procedureTypeId) {
		ProcedureType procedure = procedureTypes.findOwnById(procedureTypeId)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Procedimento não encontrado."));
		if (!procedure.isActive()) {
			throw new BusinessException(ProblemTypes.BUSINESS_RULE, "O procedimento está inativo.", INACTIVE_REASON);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, ProcedureBrief> briefs(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<UUID, ProcedureBrief> briefs = new HashMap<>();
		for (ProcedureType procedure : procedureTypes.findAllByIds(ids)) {
			briefs.put(procedure.getId(), new ProcedureBrief(procedure.getId(), procedure.getName(), procedure.getColor()));
		}
		return briefs;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, ProcedureQuote> quotes(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<UUID, ProcedureQuote> quotes = new HashMap<>();
		for (ProcedureType procedure : procedureTypes.findAllByIds(ids)) {
			quotes.put(procedure.getId(),
					new ProcedureQuote(procedure.getId(), procedure.getName(), procedure.getBasePrice(), procedure.isActive()));
		}
		return quotes;
	}
}
