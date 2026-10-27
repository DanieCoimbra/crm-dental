package crm_data.catalog.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.catalog.domain.ProcedureType;
import crm_data.catalog.repository.ProcedureTypeRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * Catálogo de procedimentos da clínica: os dois papéis leem, só o dentista grava (rota e {@code @PreAuthorize}). Não
 * existe exclusão: inativa-se. O nome ativo é único por clínica sem diferenciar caixa; o banco decide
 * ({@code ux_ptype_clinic_name}) e o handler global traduz a violação em 409, inclusive ao reativar um nome que já está em
 * uso. {@code clinicId}, {@code createdBy}, {@code id} e {@code active} nunca vêm do corpo da requisição.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DENTIST_ADMIN', 'RECEPTIONIST')")
public class ProcedureTypeService {

	/** Filtro da listagem: {@code active=true} (padrão), {@code false} ou {@code all}. */
	public enum ActiveFilter {
		ACTIVE, INACTIVE, ALL
	}

	private final ProcedureTypeRepository procedureTypes;
	private final ApplicationEventPublisher events;

	@Transactional(readOnly = true)
	public List<ProcedureType> list(ActiveFilter filter) {
		return switch (filter) {
			case ACTIVE -> procedureTypes.findByActiveOrdered(true);
			case INACTIVE -> procedureTypes.findByActiveOrdered(false);
			case ALL -> procedureTypes.findAllOrdered();
		};
	}

	/** @throws BusinessException {@code not-found} (404) se não existe na clínica */
	@Transactional(readOnly = true)
	public ProcedureType get(UUID id) {
		return load(id);
	}

	/** @throws BusinessException {@code conflict} (409) se já existe procedimento ativo com este nome (qualquer caixa) */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public ProcedureType create(String name, BigDecimal basePrice, short defaultDurationMinutes, String color) {
		ProcedureType created = procedureTypes.saveAndFlush(new ProcedureType(UUID.randomUUID(), name, basePrice,
				defaultDurationMinutes, color, TenantContext.userId()));
		events.publishEvent(new CatalogEvents.Created(TenantContext.clinicId(), created.getId(), TenantContext.userId()));
		return created;
	}

	/**
	 * Altera nome, preço, duração e cor. O preço novo vale só para itens futuros (orçamentos existentes guardam a cópia).
	 *
	 * @throws BusinessException {@code not-found} (404); {@code conflict} (409) com {@code version} defasada ou nome em uso
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public ProcedureType update(UUID id, String name, BigDecimal basePrice, short defaultDurationMinutes, String color,
			long expectedVersion) {
		ProcedureType procedure = load(id);
		requireVersion(procedure, expectedVersion);
		procedure.setName(name);
		procedure.setBasePrice(basePrice);
		procedure.setDefaultDurationMinutes(defaultDurationMinutes);
		procedure.setColor(color);
		ProcedureType saved = procedureTypes.saveAndFlush(procedure);
		events.publishEvent(new CatalogEvents.Updated(TenantContext.clinicId(), saved.getId(), TenantContext.userId()));
		return saved;
	}

	/**
	 * Ativa ou inativa. Inativar não afeta orçamentos nem agendamentos já gravados; só impede usar o procedimento daqui
	 * para frente (a regra é do backend: itens novos e agendamentos novos).
	 *
	 * @throws BusinessException {@code not-found} (404); {@code conflict} (409) com {@code version} defasada ou, ao
	 *         reativar, se outro procedimento ativo já usa o nome
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public ProcedureType setActive(UUID id, boolean active, long expectedVersion) {
		ProcedureType procedure = load(id);
		requireVersion(procedure, expectedVersion);
		procedure.setActive(active);
		ProcedureType saved = procedureTypes.saveAndFlush(procedure);
		events.publishEvent(
				new CatalogEvents.ActiveChanged(TenantContext.clinicId(), saved.getId(), saved.isActive(), TenantContext.userId()));
		return saved;
	}

	private ProcedureType load(UUID id) {
		return procedureTypes.findOwnById(id)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Procedimento não encontrado."));
	}

	private static void requireVersion(ProcedureType procedure, long expectedVersion) {
		if (!Objects.equals(procedure.getVersion(), expectedVersion)) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION);
		}
	}
}
