package crm_data.record.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.patient.service.PatientLookup;
import crm_data.record.domain.ClinicalNote;
import crm_data.record.repository.ClinicalNoteRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Notas de atendimento cifradas, exclusivas do dentista. Sem exclusão. O conteúdo não é aparado nem registrado em
 * log ou mensagem de erro. Qualquer gravação reinicia a contagem de 20 anos de retenção do prontuário.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class ClinicalNoteService {

	/** Tolerância para o relógio do cliente ao informar {@code notedAt}. */
	static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

	private final ClinicalNoteRepository notes;
	private final PatientLookup patients;
	private final EntityManager entityManager;
	private final Clock clock;

	/**
	 * @param notedAt instante do atendimento; {@code null} usa agora
	 * @throws BusinessException {@code validation} (400) se {@code notedAt} passar de agora + 5 min; {@code not-found}
	 *         (404) sem paciente; {@code conflict} (409) se anonimizado
	 */
	@Transactional
	public ClinicalNote create(UUID patientId, String content, Instant notedAt) {
		patients.requireWritable(patientId);
		ClinicalNote note = new ClinicalNote(UUID.randomUUID(), patientId, resolveNotedAt(notedAt), content,
				TenantContext.userId());
		ClinicalNote saved = notes.saveAndFlush(note);
		entityManager.refresh(saved);
		return saved;
	}

	/** Mais recentes primeiro ({@code notedAt DESC, id DESC}); decifra só a página pedida. */
	@Transactional(readOnly = true)
	public Page<ClinicalNote> list(UUID patientId, int page, int size) {
		patients.requireExists(patientId);
		return notes.findByPatientId(patientId, PageRequest.of(page, size, Sort.by(Sort.Order.desc("notedAt"), Sort.Order.desc("id"))));
	}

	/**
	 * @throws BusinessException {@code not-found} (404) se a nota não pertencer ao paciente na clínica; {@code conflict}
	 *         (409) se anonimizado ou se {@code version} estiver defasada; {@code validation} (400) para {@code notedAt}
	 *         futuro
	 */
	@Transactional
	public ClinicalNote update(UUID patientId, UUID noteId, String content, Instant notedAt, long version) {
		patients.requireWritable(patientId);
		ClinicalNote note = notes.findByIdAndPatientId(noteId, patientId)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Nota não encontrada."));
		if (note.getVersion() == null || note.getVersion() != version) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION);
		}
		note.setContent(content);
		note.setNotedAt(resolveNotedAt(notedAt));
		ClinicalNote saved = notes.saveAndFlush(note);
		entityManager.refresh(saved);
		return saved;
	}

	private Instant resolveNotedAt(Instant notedAt) {
		Instant now = Instant.now(clock);
		if (notedAt == null) {
			return now;
		}
		if (notedAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
			throw new BusinessException(ProblemTypes.VALIDATION, "A data do atendimento não pode estar no futuro.");
		}
		return notedAt;
	}
}
