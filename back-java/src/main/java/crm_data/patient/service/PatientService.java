package crm_data.patient.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.patient.domain.Patient;
import crm_data.patient.domain.Sex;
import crm_data.patient.repository.PatientRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ConstraintViolations;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Cadastro, busca, edição e inativação de pacientes. Todo acesso por id acontece DENTRO do tenant (RLS + filtro):
 * id de outra clínica é 404. Nome e CPF nunca vão para mensagem de erro nem para log.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DENTIST_ADMIN','RECEPTIONIST')")
public class PatientService {

	private static final int MIN_QUERY_LENGTH = 2;

	/** Filtro de situação da listagem. */
	public enum Status {
		ACTIVE, INACTIVE, ALL;

		public static Status parse(String value) {
			return valueOf(value.toUpperCase(Locale.ROOT));
		}
	}

	/** Dados cadastrais já normalizados e validados pela borda web; sem id, clínica, criador nem situação. */
	public record PatientDetails(String name, String cpf, LocalDate birthDate, Sex sex, String profession, String phone,
			String email, String address, String insuranceName, String insuranceNumber, String acquaintanceName,
			String acquaintancePhone) {

		/** Nunca imprime dados pessoais. */
		@Override
		public String toString() {
			return "PatientDetails[]";
		}
	}

	private final PatientRepository patients;
	private final EntityManager entityManager;
	private final Clock clock;

	/**
	 * @throws BusinessException {@code conflict} (409) se o CPF já existir na clínica
	 */
	@Transactional
	public Patient create(PatientDetails details) {
		Patient patient = new Patient(UUID.randomUUID(), TenantContext.userId());
		apply(patient, details);
		return flushAndRefresh(patient);
	}

	/**
	 * @param q trecho do nome, ou CPF (11 dígitos, com ou sem máscara); opcional, mínimo de 2 caracteres
	 * @throws BusinessException {@code validation} (400) se {@code q} tiver menos de 2 caracteres
	 */
	@Transactional(readOnly = true)
	public Page<Patient> search(String q, Status status, int page, int size) {
		String term = q == null ? "" : q.strip();
		if (!term.isEmpty() && term.length() < MIN_QUERY_LENGTH) {
			throw new BusinessException(ProblemTypes.VALIDATION, "A busca exige ao menos 2 caracteres.");
		}
		Specification<Patient> spec = Specification.unrestricted();
		if (!term.isEmpty()) {
			spec = spec.and(PatientRules.looksLikeCpfQuery(term) ? byCpf(PatientRules.normalizeCpf(term)) : byNameFragment(term));
		}
		if (status != Status.ALL) {
			boolean active = status == Status.ACTIVE;
			spec = spec.and((root, query, cb) -> cb.equal(root.get("active"), active));
		}
		return patients.findAll(spec, PageRequest.of(page, size, Sort.by("name", "id")));
	}

	/** @throws BusinessException {@code not-found} (404) se o id não existir na clínica */
	@Transactional(readOnly = true)
	public Patient get(UUID id) {
		return find(id);
	}

	/**
	 * @throws BusinessException {@code not-found} (404); {@code conflict} (409) se anonimizado, se {@code version}
	 *         estiver defasada ou se o CPF já existir na clínica
	 */
	@Transactional
	public Patient update(UUID id, PatientDetails details, long version) {
		Patient patient = find(id);
		if (patient.isAnonymized()) {
			throw new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado.");
		}
		if (patient.getVersion() == null || patient.getVersion() != version) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION);
		}
		apply(patient, details);
		return flushAndRefresh(patient);
	}

	/**
	 * Inativa ou reativa. Repetir o estado atual não altera nada (idempotente).
	 *
	 * @throws BusinessException {@code not-found} (404); {@code conflict} (409) se anonimizado
	 */
	@Transactional
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	public Patient setActive(UUID id, boolean active) {
		Patient patient = find(id);
		if (patient.isAnonymized()) {
			throw new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado.");
		}
		if (patient.isActive() == active) {
			return patient;
		}
		if (active) {
			patient.activate();
		}
		else {
			patient.deactivate(Instant.now(clock));
		}
		return flushAndRefresh(patient);
	}

	private Patient find(UUID id) {
		return patients.findById(id).orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado."));
	}

	private static void apply(Patient patient, PatientDetails details) {
		patient.setName(details.name());
		patient.setCpf(details.cpf());
		patient.setBirthDate(details.birthDate());
		patient.setSex(details.sex() != null ? details.sex() : Sex.NOT_INFORMED);
		patient.setProfession(details.profession());
		patient.setPhone(details.phone());
		patient.setEmail(details.email());
		patient.setAddress(details.address());
		patient.setInsuranceName(details.insuranceName());
		patient.setInsuranceNumber(details.insuranceNumber());
		patient.setAcquaintanceName(details.acquaintanceName());
		patient.setAcquaintancePhone(details.acquaintancePhone());
	}

	/**
	 * Grava e traz de volta {@code created_at}/{@code updated_at} (mantidos pelo banco; as colunas não são
	 * {@code @Generated}). Violação do CPF único vira 409 sem repetir o valor e sem registrar a causa.
	 */
	private Patient flushAndRefresh(Patient patient) {
		try {
			Patient saved = patients.saveAndFlush(patient);
			entityManager.refresh(saved);
			return saved;
		}
		catch (DataIntegrityViolationException ex) {
			if (ConstraintViolations.violates(ex, ConstraintViolations.PATIENT_CPF)) {
				throw new BusinessException(ProblemTypes.CONFLICT, "CPF já cadastrado.");
			}
			throw ex;
		}
	}

	private static Specification<Patient> byCpf(String cpf) {
		return (root, query, cb) -> cb.equal(root.get("cpf"), cpf);
	}

	/** {@code lower(name) LIKE %q%}: sem diferenciar caixa, sem ignorar acentos; {@code %}, {@code _} e {@code \} são texto. */
	private static Specification<Patient> byNameFragment(String term) {
		String escaped = term.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return (root, query, cb) -> cb.like(cb.lower(root.get("name")), "%" + escaped + "%", '\\');
	}
}
