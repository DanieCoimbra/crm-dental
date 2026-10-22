package crm_data.record.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import crm_data.patient.service.PatientLookup;
import crm_data.record.domain.FileKind;
import crm_data.record.domain.PatientFile;
import crm_data.record.repository.PatientFileRepository;
import crm_data.shared.config.CrmProperties;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.StorageException;
import crm_data.shared.storage.StorageKeys;
import crm_data.shared.tenant.TenantContext;

/**
 * Exames do paciente. Regra de ouro: o Storage nunca é chamado dentro de uma transação de banco (o pool tem 10
 * conexões). No upload a ordem é validar, checar o paciente, enviar ao Storage e só então gravar os metadados, com
 * compensação (apaga o objeto) se a gravação falhar. Nenhuma chave, URL ou nome de arquivo vai para log.
 *
 * <p>A recepção só anexa: lista, URL assinada e remoção são do dentista. {@link #storageKeysOf} e
 * {@link #purgeObjects} são a interface do módulo para a anonimização.
 */
@Service
@PreAuthorize("hasAnyRole('DENTIST_ADMIN','RECEPTIONIST')")
public class PatientFileService {

	/** 10 MB, o mesmo limite do multipart e da {@code CHECK} do banco. */
	public static final long MAX_SIZE_BYTES = 10_485_760L;

	private static final Logger log = LoggerFactory.getLogger(PatientFileService.class);

	/** Origem do conteúdo: aberta mais de uma vez (uma para a assinatura dos bytes, outra para o envio). */
	@FunctionalInterface
	public interface Content {
		InputStream open() throws IOException;
	}

	/** Arquivo recebido, já fora do transporte HTTP. */
	public record Upload(String kind, String declaredContentType, String originalFilename, long size, Content content) {

		/** Nunca imprime o nome do arquivo. */
		@Override
		public String toString() {
			return "Upload[]";
		}
	}

	/** URL assinada e sua validade em segundos. */
	public record SignedUrl(URI url, long expiresInSeconds) {

		/** Nunca imprime a URL. */
		@Override
		public String toString() {
			return "SignedUrl[expiresInSeconds=" + expiresInSeconds + "]";
		}
	}

	private final PatientFileRepository files;
	private final PatientLookup patients;
	private final FileStorage storage;
	private final TransactionTemplate writeTx;
	private final TransactionTemplate readTx;
	private final Clock clock;
	private final Duration signedUrlTtl;

	public PatientFileService(PatientFileRepository files, PatientLookup patients, FileStorage storage,
			PlatformTransactionManager transactionManager, Clock clock, CrmProperties properties) {
		this.files = files;
		this.patients = patients;
		this.storage = storage;
		this.writeTx = new TransactionTemplate(transactionManager);
		this.readTx = new TransactionTemplate(transactionManager);
		this.readTx.setReadOnly(true);
		this.clock = clock;
		this.signedUrlTtl = properties.storage() != null ? properties.storage().signedUrlTtl() : Duration.ofSeconds(60);
	}

	/**
	 * Anexa um exame e devolve só o id. Serve ao dentista e à recepção.
	 *
	 * @throws BusinessException {@code validation} (400) para arquivo vazio, {@code kind} inválido, tipo não permitido
	 *         ou diferente do declarado; {@code not-found} (404) sem paciente; {@code conflict} (409) se anonimizado
	 * @throws MaxUploadSizeExceededException (413) acima de 10 MB
	 * @throws StorageException (503) se o Storage falhar; nesse caso nada foi gravado no banco
	 */
	public UUID upload(UUID patientId, Upload upload) {
		FileKind kind = parseKind(upload.kind());
		if (upload.size() <= 0) {
			throw new BusinessException(ProblemTypes.VALIDATION, "O arquivo está vazio.");
		}
		if (upload.size() > MAX_SIZE_BYTES) {
			throw new MaxUploadSizeExceededException(MAX_SIZE_BYTES);
		}
		FileSignature.Type detected = detect(upload);
		String filename = FileNames.sanitize(upload.originalFilename(), detected);

		patients.requireWritable(patientId); // transação curta e própria; o Storage vem depois, fora dela

		UUID clinicId = TenantContext.clinicId();
		UUID userId = TenantContext.userId();
		UUID fileId = UUID.randomUUID();
		String key = StorageKeys.of(clinicId, patientId, fileId);

		try (InputStream in = upload.content().open()) {
			storage.save(key, in, detected.contentType(), upload.size());
		}
		catch (IOException ex) {
			throw new StorageException("Falha ao enviar o arquivo ao Storage.");
		}

		try {
			PatientFile file = new PatientFile(fileId, patientId, kind, key, filename, detected.contentType(),
					upload.size(), userId);
			writeTx.executeWithoutResult(status -> files.saveAndFlush(file));
		}
		catch (RuntimeException ex) {
			compensate(key, patientId, fileId);
			throw ex;
		}
		return fileId;
	}

	/** Exames ativos, mais recentes primeiro. Só o dentista. */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional(readOnly = true)
	public Page<PatientFile> list(UUID patientId, int page, int size) {
		patients.requireExists(patientId);
		return files.findByPatientIdAndDeletedAtIsNull(patientId,
				PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
	}

	/**
	 * URL de leitura de 60 s, só para exame ativo do paciente e da clínica do token. A busca no banco termina antes
	 * da chamada ao Storage.
	 *
	 * @throws BusinessException {@code not-found} (404) se o exame não existe, foi removido, é de outro paciente ou de
	 *         outra clínica (inclusive chave fora do prefixo da clínica)
	 * @throws StorageException (503) se o Storage falhar
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	public SignedUrl signedUrl(UUID patientId, UUID fileId) {
		String key = readTx.execute(status -> files.findByIdAndPatientIdAndDeletedAtIsNull(fileId, patientId)
				.map(PatientFile::getStorageKey).orElse(null));
		if (key == null || !StorageKeys.belongsTo(key, TenantContext.clinicId())) {
			throw new BusinessException(ProblemTypes.NOT_FOUND, "Exame não encontrado.");
		}
		try {
			return new SignedUrl(storage.createSignedUrl(key, signedUrlTtl), signedUrlTtl.toSeconds());
		}
		catch (IOException ex) {
			throw new StorageException("Falha ao assinar a URL no Storage.");
		}
	}

	/** Remoção lógica ({@code deleted_at}); o objeto permanece no Storage até a anonimização. */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public void remove(UUID patientId, UUID fileId) {
		PatientFile file = files.findByIdAndPatientIdAndDeletedAtIsNull(fileId, patientId)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Exame não encontrado."));
		file.markDeleted(Instant.now(clock));
		files.saveAndFlush(file);
	}

	/** Todas as chaves do paciente, inclusive de exames removidos logicamente (para a anonimização). */
	@Transactional(readOnly = true)
	public List<String> storageKeysOf(UUID patientId) {
		return files.findStorageKeysByPatientId(patientId);
	}

	/** Apaga os objetos em lote (para a anonimização). @throws StorageException se o Storage falhar */
	public void purgeObjects(Collection<String> keys) {
		try {
			storage.deleteAll(keys);
		}
		catch (IOException ex) {
			throw new StorageException("Falha ao apagar objetos no Storage.");
		}
	}

	private static FileKind parseKind(String kind) {
		try {
			return FileKind.valueOf(kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new BusinessException(ProblemTypes.VALIDATION, "Tipo de exame inválido.");
		}
	}

	/** Tipo real pelos bytes iniciais; o declarado pelo cliente precisa coincidir (grava-se sempre o detectado). */
	private static FileSignature.Type detect(Upload upload) {
		byte[] head;
		try (InputStream in = upload.content().open()) {
			head = in.readNBytes(FileSignature.HEADER_BYTES);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Falha ao ler o arquivo enviado.", ex);
		}
		FileSignature.Type detected = FileSignature.detect(head)
				.orElseThrow(() -> new BusinessException(ProblemTypes.VALIDATION, "Tipo de arquivo não permitido."));
		if (!detected.contentType().equals(normalize(upload.declaredContentType()))) {
			throw new BusinessException(ProblemTypes.VALIDATION, "O tipo do arquivo não confere com o seu conteúdo.");
		}
		return detected;
	}

	private static String normalize(String contentType) {
		if (contentType == null) {
			return "";
		}
		int semicolon = contentType.indexOf(';');
		return (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).strip().toLowerCase(Locale.ROOT);
	}

	/** Apaga o objeto enviado quando a gravação dos metadados falhou; se isto também falhar, registra o órfão. */
	private void compensate(String key, UUID patientId, UUID fileId) {
		try {
			storage.delete(key);
		}
		catch (IOException | RuntimeException ex) {
			log.error("File.CompensationFailed: objeto órfão no Storage patientId={} fileId={}", patientId, fileId);
		}
	}
}
