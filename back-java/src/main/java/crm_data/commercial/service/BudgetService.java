package crm_data.commercial.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.catalog.service.ProcedureQuote;
import crm_data.catalog.service.ProcedureTypeLookup;
import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetItem;
import crm_data.commercial.domain.BudgetStatus;
import crm_data.commercial.repository.BudgetDocumentRepository;
import crm_data.commercial.repository.BudgetItemRepository;
import crm_data.commercial.repository.BudgetRepository;
import crm_data.commercial.service.BudgetDraftValidator.ItemInput;
import crm_data.commercial.service.BudgetTransitions.Action;
import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.scheduling.service.AppointmentLookup;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.FieldValidationException;
import crm_data.shared.error.ProblemDetails.FieldError;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;

/**
 * Orçamento em rascunho: criar, editar, descartar, consultar e listar. Os dois papéis leem; só o dentista grava (rota e
 * {@code @PreAuthorize}). O banco calcula número, total, preço e descrição do item e os carimbos; aqui o backend orquestra
 * a <strong>ordem</strong> que o banco exige e relê a linha ({@code flush} + {@code clear} + consulta) antes de responder.
 *
 * <p>Ordem de gravação (S7): cabeçalho em {@code DRAFT} sem desconto → itens → desconto. Ao <em>substituir</em> os itens
 * (o {@code PUT} troca a lista inteira) o desconto é zerado antes e regravado depois, com {@code flush} entre as
 * etapas, para que o total do banco nunca fique negativo nem acima do teto em um estado intermediário (o Hibernate
 * executaria os {@code INSERT} antes dos {@code DELETE}). O envio, a decisão e as parcelas ficam nas F04 e F05.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DENTIST_ADMIN', 'RECEPTIONIST')")
public class BudgetService {

	/** Resumo de um documento (PDF) ativo: nunca traz a chave do objeto. */
	public record DocumentSummary(UUID id, int revision, Instant generatedAt, long sizeBytes) {
	}

	/** Conteúdo de um rascunho como veio do cliente (já validado no formato). */
	public record DraftInput(UUID appointmentId, LocalDate validUntil, String notes, BigDecimal discountAmount,
			List<ItemInput> items) {

		/** Nunca imprime observação nem valores: um log acidental não pode vazar o orçamento. */
		@Override
		public String toString() {
			return "DraftInput[items=" + items.size() + "]";
		}
	}

	/** Orçamento completo: itens, paciente resumido e documentos ativos. */
	public record BudgetView(Budget budget, List<BudgetItem> items, PatientBrief patient, List<DocumentSummary> documents) {
	}

	/** Orçamento na lista: sem itens. */
	public record BudgetSummary(Budget budget, PatientBrief patient) {
	}

	private final BudgetRepository budgets;
	private final BudgetItemRepository items;
	private final PatientLookup patients;
	private final AppointmentLookup appointments;
	private final ProcedureTypeLookup procedures;
	private final ApplicationEventPublisher events;
	private final EntityManager entityManager;
	private final BudgetDocumentRepository documents;

	// ---- escrita (só o dentista) ----

	/**
	 * Cria o rascunho com os itens e o desconto numa transação.
	 *
	 * @throws BusinessException 404 (paciente, atendimento ou procedimento), 409 (paciente anonimizado ou inativo,
	 *         atendimento de outro paciente), 422 ({@code PROCEDURE_INACTIVE})
	 * @throws FieldValidationException 400 com os campos de valor inválidos
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public BudgetView create(UUID patientId, DraftInput input) {
		patients.requireSchedulable(patientId);
		if (input.appointmentId() != null) {
			appointments.requireOfPatient(input.appointmentId(), patientId);
		}
		validate(input);

		Budget budget = budgets.saveAndFlush(new Budget(UUID.randomUUID(), patientId, input.appointmentId(), input.validUntil(),
				input.notes(), TenantContext.userId()));
		writeItems(budget.getId(), input.items());
		if (input.discountAmount().signum() > 0) {
			budget.setDiscountAmount(input.discountAmount());
			budgets.saveAndFlush(budget);
		}

		events.publishEvent(new CommercialEvents.BudgetCreated(TenantContext.clinicId(), budget.getId(), patientId,
				TenantContext.userId()));
		return reload(budget.getId());
	}

	/**
	 * Substitui os itens e atualiza desconto, validade, observação e atendimento de origem de um rascunho.
	 * {@code patientId} nunca muda.
	 *
	 * @throws BusinessException 404, 409 ({@code version} defasada ou status diferente de {@code DRAFT}), 422
	 * @throws FieldValidationException 400
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public BudgetView update(UUID id, DraftInput input, long expectedVersion) {
		Budget budget = lock(id);
		requireVersion(budget, expectedVersion);
		requireAllowed(Action.EDIT, budget);
		if (input.appointmentId() != null) {
			appointments.requireOfPatient(input.appointmentId(), budget.getPatientId());
		}
		validate(input);

		// o conteúdo mudou mesmo que nenhuma coluna do cabeçalho mude (só os itens): força o salto da versão
		entityManager.lock(budget, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
		if (budget.getDiscountAmount().signum() > 0) {
			budget.setDiscountAmount(BigDecimal.ZERO.setScale(2));
			entityManager.flush();
		}
		items.deleteAll(items.findByBudgetIdOrderByPositionAsc(id));
		entityManager.flush();
		writeItems(id, input.items());
		budget.setAppointmentId(input.appointmentId());
		budget.setValidUntil(input.validUntil());
		budget.setNotes(input.notes());
		budget.setDiscountAmount(input.discountAmount());
		entityManager.flush();

		events.publishEvent(new CommercialEvents.BudgetUpdated(TenantContext.clinicId(), id, TenantContext.userId()));
		return reload(id);
	}

	/**
	 * Descarta um rascunho ({@code DRAFT → REJECTED}); não gera PDF.
	 *
	 * @throws BusinessException 404; 409 com {@code version} defasada ou fora de {@code DRAFT}
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public BudgetView discard(UUID id, long expectedVersion) {
		Budget budget = lock(id);
		requireVersion(budget, expectedVersion);
		requireAllowed(Action.DISCARD, budget);
		budget.setStatus(BudgetTransitions.target(Action.DISCARD));
		budgets.saveAndFlush(budget);

		events.publishEvent(new CommercialEvents.BudgetDiscarded(TenantContext.clinicId(), id, TenantContext.userId()));
		return reload(id);
	}

	// ---- leitura (os dois papéis) ----

	/** @throws BusinessException {@code not-found} (404) se não existe na clínica */
	@Transactional(readOnly = true)
	public BudgetView get(UUID id) {
		return view(budgets.findById(id).orElseThrow(() -> notFound()));
	}

	/**
	 * Lista paginada, do mais recente ao mais antigo; todos os filtros são opcionais.
	 *
	 * @param statuses vazio ou {@code null} = todos
	 * @param number número exato do orçamento na clínica
	 */
	@Transactional(readOnly = true)
	public Page<BudgetSummary> list(Collection<BudgetStatus> statuses, UUID patientId, Integer number, int page, int size) {
		Specification<Budget> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (statuses != null && !statuses.isEmpty()) {
				predicates.add(root.get("status").in(statuses));
			}
			if (patientId != null) {
				predicates.add(cb.equal(root.get("patientId"), patientId));
			}
			if (number != null) {
				predicates.add(cb.equal(root.get("number"), number));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
		return summaries(budgets.findAll(spec, pageable(page, size)));
	}

	/**
	 * Orçamentos do paciente (qualquer status). Paciente inativo ou anonimizado continua consultável.
	 *
	 * @throws BusinessException {@code not-found} (404) se o paciente não existe na clínica
	 */
	@Transactional(readOnly = true)
	public Page<BudgetSummary> historyOf(UUID patientId, int page, int size) {
		patients.requireExists(patientId);
		Specification<Budget> spec = (root, query, cb) -> cb.equal(root.get("patientId"), patientId);
		return summaries(budgets.findAll(spec, pageable(page, size)));
	}

	// ---- auxiliares ----

	private static Pageable pageable(int page, int size) {
		return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
	}

	private Page<BudgetSummary> summaries(Page<Budget> found) {
		Map<UUID, PatientBrief> briefs = patients
				.briefs(found.getContent().stream().map(Budget::getPatientId).distinct().toList());
		return found.map(budget -> new BudgetSummary(budget, briefs.get(budget.getPatientId())));
	}

	/** Procedimentos existentes e ativos (404 / 422) e limites de valor (400 com os campos). */
	private void validate(DraftInput input) {
		Map<UUID, ProcedureQuote> quotes = procedures.quotes(input.items().stream().map(ItemInput::procedureTypeId).distinct().toList());
		for (ItemInput item : input.items()) {
			ProcedureQuote quote = quotes.get(item.procedureTypeId());
			if (quote == null) {
				throw new BusinessException(ProblemTypes.NOT_FOUND, "Procedimento não encontrado.");
			}
			if (!quote.active()) {
				throw new BusinessException(ProblemTypes.BUSINESS_RULE, "O procedimento está inativo.", "PROCEDURE_INACTIVE");
			}
		}
		List<FieldError> errors = BudgetDraftValidator.validate(input.items(), quotes, input.discountAmount());
		if (!errors.isEmpty()) {
			throw new FieldValidationException(errors);
		}
	}

	/** Grava os itens nas posições 1..n e faz {@code flush} (o banco copia descrição e preço e recalcula o total). */
	private void writeItems(UUID budgetId, List<ItemInput> inputs) {
		List<BudgetItem> created = new ArrayList<>(inputs.size());
		for (int i = 0; i < inputs.size(); i++) {
			ItemInput input = inputs.get(i);
			created.add(new BudgetItem(UUID.randomUUID(), budgetId, input.procedureTypeId(), (short) input.quantity(),
					input.discountAmount(), (short) (i + 1)));
		}
		items.saveAll(created);
		entityManager.flush();
	}

	private Budget lock(UUID id) {
		return budgets.findForUpdateById(id).orElseThrow(() -> notFound());
	}

	private static void requireVersion(Budget budget, long expectedVersion) {
		if (!Objects.equals(budget.getVersion(), expectedVersion)) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION);
		}
	}

	private static void requireAllowed(Action action, Budget budget) {
		if (!BudgetTransitions.allowedFrom(action, budget.getStatus())) {
			throw new BusinessException(ProblemTypes.CONFLICT, BudgetTransitions.refusal(action, budget.getStatus()));
		}
	}

	private static BusinessException notFound() {
		return new BusinessException(ProblemTypes.NOT_FOUND, "Orçamento não encontrado.");
	}

	/**
	 * Relê o orçamento e os itens do banco: o total, o número e o preço dos itens foram preenchidos por trigger e as
	 * entidades gerenciadas estão desatualizadas. Limpar o contexto garante que a consulta traga a linha nova.
	 */
	private BudgetView reload(UUID id) {
		return reloaded(id);
	}

	/**
	 * Descarrega as gravações, esvazia o contexto e lê o orçamento de novo (usado depois de qualquer mudança de status
	 * ou de conteúdo). Público para o {@link BudgetDecisionService}; só funciona dentro de uma transação.
	 */
	public BudgetView reloaded(UUID id) {
		entityManager.flush();
		entityManager.clear();
		return view(budgets.findById(id).orElseThrow(() -> notFound()));
	}

	private BudgetView view(Budget budget) {
		List<BudgetItem> found = items.findByBudgetIdOrderByPositionAsc(budget.getId());
		PatientBrief patient = patients.briefs(List.of(budget.getPatientId())).get(budget.getPatientId());
		List<DocumentSummary> active = documents.findActiveByBudgetId(budget.getId()).stream()
				.map(d -> new DocumentSummary(d.getId(), d.getRevision(), d.getGeneratedAt(), d.getSizeBytes())).toList();
		return new BudgetView(budget, found, patient, active);
	}
}
