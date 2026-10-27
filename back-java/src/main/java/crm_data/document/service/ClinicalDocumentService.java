package crm_data.document.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import crm_data.auth.service.ProfessionalIdentity;
import crm_data.clinic.service.ClinicZones;
import crm_data.clinic.service.UserNames;
import crm_data.document.domain.ClinicalDocument;
import crm_data.document.domain.DocumentState;
import crm_data.document.domain.DocumentType;
import crm_data.document.repository.ClinicalDocumentRepository;
import crm_data.document.repository.DocumentSummaryRow;
import crm_data.patient.service.PatientLookup;
import crm_data.scheduling.service.AppointmentLookup;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.FieldValidationException;
import crm_data.shared.error.ProblemDetails.FieldError;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Documentos clínicos (atestado, receita, encaminhamento), exclusivos do dentista. O texto é cifrado UMA vez na emissão e o
 * {@code sha256} cobre o texto cifrado; a leitura confere o selo antes de decifrar. O documento é imutável: a única
 * mutação é a anulação. Número, auditoria e anonimização são do banco. O texto e o motivo nunca vão para log ou exceção.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class ClinicalDocumentService {

	private final ClinicalDocumentRepository documents;
	private final PatientLookup patients;
	private final AppointmentLookup appointments;
	private final ProfessionalIdentity professional;
	private final UserNames userNames;
	private final ClinicZones zones;
	private final DocumentCipher cipher;
	private final EntityManager entityManager;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	/** Linha da lista (sem o texto), com o nome do emissor e do anulador resolvidos e o estado derivado. */
	public record SummaryView(UUID id, UUID patientId, UUID appointmentId, DocumentType type, Integer number, LocalDate issuedOn,
			Short restDays, String targetSpecialty, DocumentState state, Instant voidedAt, String voidedByName, Instant createdAt,
			String createdByName) {
	}

	/**
	 * Emite um documento. {@code content} e {@code specialty} chegam aparados (vazio = nulo).
	 *
	 * @param issuedOn {@code null} = hoje no fuso da clínica
	 * @throws FieldValidationException 400 para combinação inválida de campos por tipo
	 * @throws BusinessException {@code not-found} (404) sem paciente ou consulta; {@code conflict} (409) paciente inativo ou
	 *         anonimizado ou emissor sem CRO; {@code business-rule} (422) para data futura ou consulta de outro paciente
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public DocumentView create(UUID patientId, DocumentType type, LocalDate issuedOn, Integer restDays, String specialty,
			String content, UUID appointmentId) {
		List<FieldError> errors = DocumentRules.validate(type, restDays, specialty, content);
		if (!errors.isEmpty()) {
			throw new FieldValidationException(errors);
		}
		patients.requireSchedulable(patientId);
		LocalDate today = LocalDate.now(clock.withZone(zones.zone()));
		LocalDate effectiveDate = issuedOn == null ? today : issuedOn;
		if (effectiveDate.isAfter(today)) {
			throw new BusinessException(ProblemTypes.BUSINESS_RULE, "A data de emissão não pode estar no futuro.",
					"ISSUED_ON_IN_FUTURE");
		}
		if (appointmentId != null) {
			requireAppointmentOfPatient(appointmentId, patientId);
		}
		if (!professional.current().hasRegistry()) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.PROFESSIONAL_REGISTRY_MISSING);
		}

		Short days = restDays == null ? null : restDays.shortValue();
		String cipherText = cipher.encrypt(content);
		String seal = DocumentIntegrity.compute(type, patientId, effectiveDate, days, specialty, cipherText);
		ClinicalDocument document = new ClinicalDocument(UUID.randomUUID(), patientId, appointmentId, type, effectiveDate, days,
				specialty, cipherText, seal, TenantContext.userId());
		ClinicalDocument saved = documents.saveAndFlush(document);
		entityManager.refresh(saved); // lê o número atribuído pelo banco e os carimbos de tempo
		events.publishEvent(new DocumentEvents.Issued(TenantContext.clinicId(), saved.getId(), patientId, type.name(),
				TenantContext.userId()));
		Map<UUID, String> names = userNames.namesOf(List.of(saved.getCreatedBy()));
		return new DocumentView(saved, content, names.get(saved.getCreatedBy()), null);
	}

	/** Mais recentes primeiro ({@code createdAt DESC, id DESC}); sem o texto; nomes em lote. */
	@Transactional(readOnly = true)
	public Page<SummaryView> list(UUID patientId, DocumentType type, int page, int size) {
		patients.requireExists(patientId);
		PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		Page<DocumentSummaryRow> rows = type == null ? documents.summaries(patientId, pageable)
				: documents.summariesOfType(patientId, type, pageable);
		Set<UUID> userIds = new HashSet<>();
		rows.forEach(row -> {
			userIds.add(row.createdBy());
			if (row.voidedBy() != null) {
				userIds.add(row.voidedBy());
			}
		});
		Map<UUID, String> names = userNames.namesOf(userIds);
		return rows.map(row -> new SummaryView(row.id(), row.patientId(), row.appointmentId(), row.type(), row.number(),
				row.issuedOn(), row.restDays(), row.targetSpecialty(), stateOf(row), row.voidedAt(),
				row.voidedBy() == null ? null : names.get(row.voidedBy()), row.createdAt(), names.get(row.createdBy())));
	}

	/**
	 * Abre o documento: confere o selo e decifra o texto (nulo se anonimizado).
	 *
	 * @throws BusinessException {@code not-found} (404) se não existe ou é de outra clínica
	 * @throws crm_data.shared.error.IntegrityException se o selo não confere
	 */
	@Transactional(readOnly = true)
	public DocumentView get(UUID id) {
		return view(load(id));
	}

	/**
	 * Anula o documento (uma vez). Só {@code voided_at}, {@code voided_by}, {@code void_reason} e {@code version} são
	 * gravados; a auditoria {@code VOID} é do trigger do banco.
	 *
	 * @param reason já aparado; {@code null} se vazio
	 * @throws BusinessException {@code not-found} (404); {@code conflict} (409) se já anulado, anonimizado ou versão defasada
	 */
	@Transactional
	public DocumentView voidDocument(UUID id, long version, String reason) {
		ClinicalDocument document = load(id);
		if (document.getAnonymizedAt() != null || document.getVoidedAt() != null) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.DOCUMENT_STATE);
		}
		if (document.getVersion() == null || document.getVersion() != version) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION);
		}
		document.markVoided(clock.instant(), TenantContext.userId(), reason);
		ClinicalDocument saved = documents.saveAndFlush(document);
		entityManager.refresh(saved);
		events.publishEvent(new DocumentEvents.Voided(TenantContext.clinicId(), saved.getId(), saved.getPatientId(),
				TenantContext.userId()));
		return view(saved);
	}

	private static DocumentState stateOf(DocumentSummaryRow row) {
		if (row.anonymizedAt() != null) {
			return DocumentState.ANONYMIZED;
		}
		return row.voidedAt() != null ? DocumentState.VOIDED : DocumentState.ACTIVE;
	}

	private ClinicalDocument load(UUID id) {
		return documents.findOwnById(id)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Documento não encontrado."));
	}

	/** Confere o selo, decifra (se não anonimizado) e resolve os nomes. */
	private DocumentView view(ClinicalDocument document) {
		DocumentIntegrity.verify(document);
		String content = document.state() == DocumentState.ANONYMIZED ? null : cipher.decrypt(document.getContent());
		Set<UUID> ids = new HashSet<>();
		ids.add(document.getCreatedBy());
		if (document.getVoidedBy() != null) {
			ids.add(document.getVoidedBy());
		}
		Map<UUID, String> names = userNames.namesOf(ids);
		return new DocumentView(document, content, names.get(document.getCreatedBy()),
				document.getVoidedBy() == null ? null : names.get(document.getVoidedBy()));
	}

	/** O lookup responde 409 para consulta de outro paciente; a regra do documento é 422 com código estável. */
	private void requireAppointmentOfPatient(UUID appointmentId, UUID patientId) {
		try {
			appointments.requireOfPatient(appointmentId, patientId);
		}
		catch (BusinessException ex) {
			if (ex.type() == ProblemTypes.CONFLICT) {
				throw new BusinessException(ProblemTypes.BUSINESS_RULE, "A consulta pertence a outro paciente.",
						"APPOINTMENT_OF_OTHER_PATIENT");
			}
			throw ex;
		}
	}
}
