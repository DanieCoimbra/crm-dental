package crm_data.patient.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.record.service.PatientFileService;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.DatabaseErrors;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.storage.StorageException;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;

/**
 * Anonimização LGPD do paciente, só pelo dentista, em duas fases. O service NÃO é transacional: usa
 * {@link TransactionTemplate} para separar o commit do banco da limpeza do Storage.
 *
 * <ol>
 * <li><b>Banco</b> (uma transação): {@code core.anonymize_patient} (que valida papel, clínica, inatividade e retenção
 * e trava a linha do paciente) e, logo depois e na mesma transação, a lista de todas as chaves de objeto do paciente.
 * Listar depois da função fecha a corrida com um upload concorrente, que espera na FK e falha com {@code CR003}.</li>
 * <li><b>Storage</b> (depois do commit): apaga os objetos. Se falhar, a anonimização é mantida e a resposta é
 * {@code PENDING}.</li>
 * </ol>
 * Logs e eventos levam só identificadores e contagens, nunca nome, CPF, chave ou nome de arquivo.
 */
@Service
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class PatientAnonymizationService {

	private static final Logger log = LoggerFactory.getLogger(PatientAnonymizationService.class);

	/** Situação da limpeza dos objetos no Storage. */
	public enum StorageCleanup {
		COMPLETED, PENDING
	}

	/**
	 * Resultado devolvido ao cliente.
	 *
	 * @param openBudgets orçamentos em aberto ({@code DRAFT}/{@code SENT}) do paciente, só como aviso
	 */
	public record Result(Instant anonymizedAt, StorageCleanup storageCleanup, int openBudgets) {
	}

	private record DatabasePhase(Instant anonymizedAt, List<String> storageKeys, int openBudgets) {

		/** Nunca imprime as chaves. */
		@Override
		public String toString() {
			return "DatabasePhase[objects=" + storageKeys.size() + "]";
		}
	}

	private final EntityManager entityManager;
	private final PatientFileService files;
	private final ApplicationEventPublisher events;
	private final TransactionTemplate tx;
	private final List<AnonymizationContributor> contributors;

	public PatientAnonymizationService(EntityManager entityManager, PatientFileService files,
			PlatformTransactionManager transactionManager, ApplicationEventPublisher events,
			List<AnonymizationContributor> contributors) {
		this.entityManager = entityManager;
		this.files = files;
		this.contributors = List.copyOf(contributors);
		this.events = events;
		this.tx = new TransactionTemplate(transactionManager);
	}

	/**
	 * @throws BusinessException {@code not-found} (404) se o paciente não existe na clínica; {@code conflict} (409) se
	 *         ainda está ativo, se a retenção legal não venceu (com a data de liberação) ou se já foi anonimizado;
	 *         {@code forbidden} (403) se o papel não for dentista
	 */
	public Result anonymize(UUID patientId) {
		UUID clinicId = TenantContext.clinicId();

		DatabasePhase database;
		try {
			database = tx.execute(status -> {
				Instant anonymizedAt = toInstant(entityManager.createNativeQuery("SELECT core.anonymize_patient(:id)")
						.setParameter("id", patientId).getSingleResult());
				// listar depois da função, na mesma transação: um upload concorrente falha com CR003 e nada escapa da lista
				List<String> keys = new ArrayList<>(files.storageKeysOf(patientId));
				int open = 0;
				for (AnonymizationContributor contributor : contributors) {
					keys.addAll(contributor.objectKeys(patientId));
					open += contributor.openItems(patientId);
				}
				return new DatabasePhase(anonymizedAt, List.copyOf(keys), open);
			});
		}
		catch (RuntimeException ex) {
			throw translate(ex);
		}

		StorageCleanup cleanup = purge(patientId, database.storageKeys());
		events.publishEvent(new PatientEvents.Anonymized(clinicId, patientId, database.storageKeys().size(), cleanup));
		return new Result(database.anonymizedAt(), cleanup, database.openBudgets());
	}

	/**
	 * Depois do commit: nenhuma falha da limpeza desfaz a anonimização nem vira erro para o cliente. {@link
	 * StorageException} é o caso esperado; qualquer outra exceção também resulta em {@code PENDING} (só a classe vai ao
	 * log, pois a mensagem poderia citar chaves), para que o dado já anonimizado não seja reportado como falha.
	 */
	private StorageCleanup purge(UUID patientId, List<String> keys) {
		if (keys.isEmpty()) {
			return StorageCleanup.COMPLETED;
		}
		try {
			files.purgeObjects(keys);
			return StorageCleanup.COMPLETED;
		}
		catch (StorageException ex) {
			return pending(patientId, keys.size());
		}
		catch (RuntimeException ex) {
			log.error("Falha inesperada na limpeza do Storage após a anonimização: patientId={} erro={}", patientId,
					ex.getClass().getName());
			return pending(patientId, keys.size());
		}
	}

	private StorageCleanup pending(UUID patientId, int objectCount) {
		events.publishEvent(new PatientEvents.StorageCleanupPending(patientId, objectCount));
		return StorageCleanup.PENDING;
	}

	/** Traduz o {@code SQLState} da função em erro de negócio; qualquer outro erro segue como veio (vira 500). */
	private static RuntimeException translate(RuntimeException error) {
		String state = DatabaseErrors.sqlState(error).orElse("");
		return switch (state) {
			case "CR001" -> new BusinessException(ProblemTypes.CONFLICT, "Inative o paciente antes de anonimizar.");
			case "CR002" -> new BusinessException(ProblemTypes.CONFLICT, DatabaseErrors.retentionReleaseDate(error)
					.map(date -> "Retenção legal do prontuário vigente. Liberado em " + date + ".")
					.orElse("Retenção legal do prontuário vigente."));
			case "CR003" -> new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado.");
			case "CR004" -> new BusinessException(ProblemTypes.CONFLICT, "Cancele os agendamentos futuros antes de anonimizar.");
			case "CR006" -> new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.OPEN_INSTALLMENTS);
			case "P0002" -> new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado.");
			case "42501" -> new BusinessException(ProblemTypes.FORBIDDEN, "Você não tem permissão para esta operação.");
			default -> error;
		};
	}

	/** O driver devolve {@code timestamptz} como {@code Instant}, {@code OffsetDateTime} ou {@code Timestamp}. */
	private static Instant toInstant(Object value) {
		if (value instanceof Instant instant) {
			return instant;
		}
		if (value instanceof OffsetDateTime offset) {
			return offset.toInstant();
		}
		if (value instanceof ZonedDateTime zoned) {
			return zoned.toInstant();
		}
		if (value instanceof java.sql.Timestamp timestamp) {
			return timestamp.toInstant();
		}
		throw new IllegalStateException("Resposta inesperada de core.anonymize_patient.");
	}
}
