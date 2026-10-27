package crm_data.commercial.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.clinic.service.ClinicIdentity;
import crm_data.clinic.service.ClinicIdentity.ClinicInfo;
import crm_data.clinic.service.ClinicZones;
import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetDocument;
import crm_data.commercial.domain.BudgetItem;
import crm_data.commercial.repository.BudgetDocumentRepository;
import crm_data.commercial.repository.BudgetItemRepository;
import crm_data.commercial.repository.BudgetRepository;
import crm_data.commercial.service.BudgetService.BudgetView;
import crm_data.commercial.service.BudgetService.DocumentSummary;
import crm_data.commercial.service.BudgetTransitions.Action;
import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.config.CrmProperties;
import crm_data.shared.config.StorageConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.BusyException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.StorageException;
import crm_data.shared.storage.StorageKeys;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;

/**
 * Envio do orçamento e PDF (H1). O envio muda o orçamento para {@code SENT}, gera o PDF, grava o objeto no Storage e
 * registra o metadado, <strong>tudo na mesma transação</strong>, para que o orçamento só fique {@code SENT} com o PDF
 * registrado. É a única exceção à regra "o Storage nunca é chamado dentro de uma transação de banco" e só usa o bean
 * {@code documentStorage} (leitura de 10 s). A contenção do pool de 10 conexões vem de três limites: {@link DocumentSlots}
 * (envios simultâneos), {@code DocumentRateLimiter} (por usuário, no interceptor) e o teto de tempo da transação.
 *
 * <p><b>Compensação.</b> A chave do objeto é registrada <em>antes</em> de gravar (um upload parcial também é limpo). Se a
 * transação falhar por qualquer motivo, o objeto é apagado. Se a falha veio do <em>commit</em> (resultado incerto: a
 * conexão pode ter caído depois do ack), confere-se antes se a linha de {@code budget_documents} existe e, existindo, o
 * objeto <strong>não</strong> é apagado. Falha ao apagar vira o evento {@code StorageCompensationFailed} (só o
 * orçamento); a resposta ao cliente continua sendo a do erro original. Nenhuma chave, URL ou nome vai para log.
 *
 * <p>O download é por URL assinada de curta duração, gerada <em>fora</em> de transação depois de checar tenant e papel;
 * nunca devolve a {@code storage_key}.
 */
@Service
@PreAuthorize("hasAnyRole('DENTIST_ADMIN', 'RECEPTIONIST')")
public class BudgetDocumentService {

	/** Teto do PDF: o mesmo da {@code CHECK} do banco (5 MB). */
	static final int MAX_PDF_BYTES = 5 * 1024 * 1024;

	private static final Logger log = LoggerFactory.getLogger(BudgetDocumentService.class);

	/** URL assinada de download e sua validade; nunca imprime a URL. */
	public record DownloadUrl(URI url, long expiresInSeconds) {

		@Override
		public String toString() {
			return "DownloadUrl[expiresInSeconds=" + expiresInSeconds + "]";
		}
	}

	private final BudgetRepository budgets;
	private final BudgetItemRepository items;
	private final BudgetDocumentRepository documents;
	private final PatientLookup patients;
	private final ClinicIdentity clinic;
	private final ClinicZones zones;
	private final FileStorage storage;
	private final DocumentSlots slots;
	private final ApplicationEventPublisher events;
	private final EntityManager entityManager;
	private final Clock clock;
	private final BudgetService budgetService;
	private final TransactionTemplate writeTx;
	private final TransactionTemplate readTx;
	private final Duration signedUrlTtl;
	private BudgetPdfRenderer renderer = new BudgetPdfRenderer(); // não final: o teste troca por um que devolve PDF gigante

	public BudgetDocumentService(BudgetRepository budgets, BudgetItemRepository items, BudgetDocumentRepository documents,
			PatientLookup patients, ClinicIdentity clinic, ClinicZones zones,
			@Qualifier(StorageConfig.DOCUMENT_STORAGE) FileStorage storage, DocumentSlots slots,
			ApplicationEventPublisher events, EntityManager entityManager, Clock clock, BudgetService budgetService,
			PlatformTransactionManager transactionManager, CrmProperties properties) {
		this.budgets = budgets;
		this.items = items;
		this.documents = documents;
		this.patients = patients;
		this.clinic = clinic;
		this.zones = zones;
		this.storage = storage;
		this.slots = slots;
		this.events = events;
		this.entityManager = entityManager;
		this.clock = clock;
		this.budgetService = budgetService;
		this.writeTx = new TransactionTemplate(transactionManager);
		this.writeTx.setTimeout((int) properties.documents().transactionTimeout().toSeconds());
		this.readTx = new TransactionTemplate(transactionManager);
		this.readTx.setReadOnly(true);
		this.signedUrlTtl = properties.storage() != null ? properties.storage().signedUrlTtl() : Duration.ofSeconds(60);
	}

	// ---- escrita (só o dentista) ----

	/**
	 * Envia o orçamento: {@code DRAFT → SENT} e PDF da revisão 1, atomicamente.
	 *
	 * @throws BusinessException 404; 409 (sem itens ou total zero, {@code version} defasada, status fora de {@code DRAFT})
	 *         ; 422 ({@code PDF_TOO_LARGE})
	 * @throws BusyException 503 sem vaga de envio
	 * @throws StorageException 503 se o Storage falhar (nada foi gravado no banco)
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	public BudgetView send(UUID budgetId, long expectedVersion) {
		publish(budgetId, Action.SEND, expectedVersion);
		return budgetService.get(budgetId);
	}

	/**
	 * Nova revisão do PDF de um orçamento {@code SENT}/{@code APPROVED}/{@code REJECTED}, sem mudar o status; a revisão
	 * anterior é conservada.
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	public DocumentSummary regenerate(UUID budgetId) {
		Published published = publish(budgetId, Action.REGENERATE_DOCUMENT, null);
		return new DocumentSummary(published.documentId(), published.revision(), published.generatedAt(), published.sizeBytes());
	}

	// ---- leitura (os dois papéis) ----

	/** Revisões ativas, a maior primeiro. @throws BusinessException {@code not-found} (404) se o orçamento não existe */
	public List<DocumentSummary> list(UUID budgetId) {
		return readTx.execute(status -> {
			budgets.findOwnById(budgetId).orElseThrow(() -> notFound());
			return documents.findActiveByBudgetId(budgetId).stream().map(BudgetDocumentService::summary).toList();
		});
	}

	/**
	 * URL assinada de leitura. A busca no banco termina antes da chamada ao Storage. Documento removido, de outro
	 * orçamento ou de outra clínica é 404.
	 */
	public DownloadUrl downloadUrl(UUID budgetId, UUID documentId) {
		String key = readTx.execute(status -> documents.findByIdAndBudgetIdAndDeletedAtIsNull(documentId, budgetId)
				.map(BudgetDocument::getStorageKey).orElse(null));
		if (key == null || !StorageKeys.belongsTo(key, TenantContext.clinicId())) {
			throw new BusinessException(ProblemTypes.NOT_FOUND, "Documento não encontrado.");
		}
		try {
			return new DownloadUrl(storage.createSignedUrl(key, signedUrlTtl), signedUrlTtl.toSeconds());
		}
		catch (IOException ex) {
			throw new StorageException("Falha ao assinar a URL no Storage.");
		}
	}

	// ---- fluxo H1 ----

	/** Documento recém-registrado, com a revisão e o instante que o banco atribuiu. */
	private record Published(UUID documentId, UUID patientId, int revision, Instant generatedAt, long sizeBytes) {
	}

	private Published publish(UUID budgetId, Action action, Long expectedVersion) {
		if (!slots.tryAcquire()) {
			throw new BusyException("Muitos envios em andamento. Tente novamente em instantes.", 5);
		}
		UUID clinicId = TenantContext.clinicId();
		UUID userId = TenantContext.userId();
		AtomicReference<String> objectKey = new AtomicReference<>();
		AtomicBoolean bodyCompleted = new AtomicBoolean(false);
		try {
			Published published = writeTx.execute(status -> {
				Published created = generate(budgetId, action, expectedVersion, userId, objectKey);
				bodyCompleted.set(true);
				return created;
			});
			if (action == Action.SEND) {
				events.publishEvent(new CommercialEvents.BudgetSent(clinicId, budgetId, published.patientId(), published.revision(), userId));
			}
			else {
				events.publishEvent(new CommercialEvents.DocumentGenerated(clinicId, budgetId, published.revision(), userId));
			}
			return published;
		}
		catch (RuntimeException ex) {
			compensate(clinicId, budgetId, objectKey.get(), bodyCompleted.get());
			throw ex;
		}
		finally {
			slots.release();
		}
	}

	/** Corpo da transação: muda o status (envio), gera o PDF, grava o objeto e registra o metadado. */
	private Published generate(UUID budgetId, Action action, Long expectedVersion, UUID userId,
			AtomicReference<String> objectKey) {
		Budget budget = budgets.findForUpdateById(budgetId).orElseThrow(() -> notFound());
		if (!BudgetTransitions.allowedFrom(action, budget.getStatus())) {
			throw new BusinessException(ProblemTypes.CONFLICT, BudgetTransitions.refusal(action, budget.getStatus()));
		}
		// trava o paciente (compartilhado): a anonimização concorrente espera este envio e inclui o PDF novo na limpeza; se já
		// anonimizou, este envio falha com 409 (o INSERT do metadado, sozinho, não olha anonymized_at)
		patients.lockWritable(budget.getPatientId());
		List<BudgetItem> lines = items.findByBudgetIdOrderByPositionAsc(budgetId);
		if (action == Action.SEND) {
			if (!Objects.equals(budget.getVersion(), expectedVersion)) {
				throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION);
			}
			if (lines.isEmpty() || budget.getTotalAmount().signum() <= 0) {
				throw new BusinessException(ProblemTypes.CONFLICT, "Inclua ao menos um item com valor maior que zero.");
			}
			budget.setStatus(BudgetTransitions.target(Action.SEND));
			budgets.saveAndFlush(budget);
			entityManager.refresh(budget); // o banco carimbou sent_at
		}

		int revision = documents.maxRevision(budgetId) + 1;
		byte[] pdf = renderer.render(modelOf(budget, lines, revision));
		if (pdf.length > MAX_PDF_BYTES) {
			throw new BusinessException(ProblemTypes.BUSINESS_RULE, "O PDF do orçamento passou do tamanho permitido.", "PDF_TOO_LARGE");
		}
		String sha256 = sha256Hex(pdf);

		String key = StorageKeys.ofBudget(TenantContext.clinicId(), budget.getPatientId(), UUID.randomUUID());
		objectKey.set(key); // antes de gravar: até um upload parcial é limpo na compensação
		try {
			storage.save(key, new ByteArrayInputStream(pdf), BudgetDocument.CONTENT_TYPE, pdf.length);
		}
		catch (IOException ex) {
			throw new StorageException("Falha ao enviar o PDF ao Storage.");
		}

		BudgetDocument document = documents.saveAndFlush(
				new BudgetDocument(UUID.randomUUID(), budgetId, budget.getPatientId(), key, pdf.length, sha256, userId));
		BudgetDocumentRepository.IssuedRow issued = documents.findIssuedById(document.getId())
				.orElseThrow(() -> new IllegalStateException("Documento recém-registrado não encontrado."));
		return new Published(document.getId(), budget.getPatientId(), issued.getRevision(), issued.getGeneratedAt(), pdf.length);
	}

	private BudgetPdfModel modelOf(Budget budget, List<BudgetItem> lines, int revision) {
		ZoneId zone = zones.zone();
		LocalDate issuedOn = LocalDate.now(clock.withZone(zone));
		LocalDate sentOn = budget.getSentAt() != null ? budget.getSentAt().atZone(zone).toLocalDate() : null; // nunca enviado (rascunho descartado)
		ClinicInfo clinicInfo = clinic.current();
		PatientBrief patient = patients.briefs(List.of(budget.getPatientId())).get(budget.getPatientId());
		BigDecimal subtotal = lines.stream().map(BudgetItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
		List<BudgetPdfModel.Line> modelLines = lines.stream().map(line -> new BudgetPdfModel.Line(line.getDescription(),
				line.getQuantity(), line.getUnitPrice(), line.getDiscountAmount(), line.getLineTotal())).toList();
		return new BudgetPdfModel(clinicInfo.name(), clinicInfo.address(), clinicInfo.phone(),
				patient == null ? "" : patient.name(), budget.getNumber(), revision, sentOn, issuedOn, budget.getValidUntil(),
				modelLines, subtotal, budget.getDiscountAmount(), budget.getTotalAmount(), budget.getNotes());
	}

	/**
	 * Apaga o objeto de uma transação que falhou. Se o corpo terminou e o erro veio do commit, o resultado é incerto:
	 * confere antes se a linha existe e, existindo, mantém o objeto.
	 */
	private void compensate(UUID clinicId, UUID budgetId, String key, boolean bodyCompleted) {
		if (key == null) {
			return;
		}
		try {
			if (bodyCompleted && Boolean.TRUE.equals(readTx.execute(status -> documents.existsByStorageKey(key)))) {
				return; // o commit aconteceu: o objeto é o do documento registrado
			}
			storage.delete(key);
		}
		catch (IOException | RuntimeException ex) {
			events.publishEvent(new CommercialEvents.StorageCompensationFailed(clinicId, budgetId));
			log.error("Falha na compensação do Storage do orçamento: budgetId={} erro={}", budgetId, ex.getClass().getName());
		}
	}

	private static DocumentSummary summary(BudgetDocument document) {
		return new DocumentSummary(document.getId(), document.getRevision(), document.getGeneratedAt(), document.getSizeBytes());
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 indisponível.", ex);
		}
	}

	private static BusinessException notFound() {
		return new BusinessException(ProblemTypes.NOT_FOUND, "Orçamento não encontrado.");
	}
}
