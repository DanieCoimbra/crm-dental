package crm_data.record.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.patient.service.PatientLookup;
import crm_data.record.domain.MedicalHistory;
import crm_data.record.repository.MedicalHistoryRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ConstraintViolations;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Histórico médico cifrado, um por paciente, exclusivo do dentista. A cifra é do conversor da entidade: este service
 * nunca toca a chave. O conteúdo não é aparado nem registrado em log ou mensagem de erro. Qualquer gravação reinicia
 * a contagem de 20 anos de retenção do prontuário (o banco a calcula pelo último registro).
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class MedicalHistoryService {

	/** Resultado do upsert: o histórico e se ele foi criado agora (201) ou atualizado (200). */
	public record Saved(MedicalHistory history, boolean created) {
	}

	private final MedicalHistoryRepository histories;
	private final PatientLookup patients;
	private final EntityManager entityManager;

	/** @throws BusinessException {@code not-found} (404) se o paciente não existir ou o histórico ainda não existir */
	@Transactional(readOnly = true)
	public MedicalHistory get(UUID patientId) {
		patients.requireExists(patientId);
		return histories.findByPatientId(patientId)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Histórico não encontrado."));
	}

	/**
	 * Cria o histórico na primeira vez (a {@code version} enviada é ignorada) e o atualiza depois, exigindo a versão
	 * atual.
	 *
	 * @throws BusinessException {@code not-found} (404) sem paciente; {@code conflict} (409) se o paciente estiver
	 *         anonimizado, se o histórico já existir e a {@code version} faltar ou estiver defasada, ou se outra
	 *         requisição criou o histórico ao mesmo tempo
	 */
	@Transactional
	public Saved save(UUID patientId, String content, Long version) {
		patients.requireWritable(patientId);
		Optional<MedicalHistory> existing = histories.findByPatientId(patientId);
		boolean created = existing.isEmpty();
		MedicalHistory history;
		if (created) {
			history = new MedicalHistory(UUID.randomUUID(), patientId, content, TenantContext.userId());
		}
		else {
			history = existing.get();
			if (version == null) {
				throw new BusinessException(ProblemTypes.CONFLICT, "Histórico já existe; envie a versão atual.");
			}
			if (!version.equals(history.getVersion())) {
				throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION);
			}
			history.setContent(content);
		}
		try {
			MedicalHistory saved = histories.saveAndFlush(history);
			entityManager.refresh(saved);
			return new Saved(saved, created);
		}
		catch (DataIntegrityViolationException ex) {
			if (ConstraintViolations.violates(ex, ConstraintViolations.HISTORY_PATIENT)) {
				throw new BusinessException(ProblemTypes.CONFLICT, "Histórico já existe.");
			}
			throw ex;
		}
	}
}
