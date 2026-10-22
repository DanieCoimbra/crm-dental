package crm_data.record.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.clinic.service.UserNames;
import crm_data.record.domain.AuditLog;
import crm_data.record.repository.AuditLogRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import lombok.RequiredArgsConstructor;

/**
 * Consulta da trilha de auditoria, só do dentista e somente leitura. A trilha guarda identificadores (quem, qual
 * tabela, qual registro, qual ação, quando), nunca valores de colunas, e é por registro: não há filtro por paciente.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class AuditQueryService {

	/** Tabelas auditadas (constraint {@code ck_audit_table}). */
	static final Set<String> TABLES = Set.of("patients", "medical_histories", "clinical_notes", "patient_files");

	/** Entrada da página com o nome do usuário já resolvido ({@code null} se não encontrado). */
	public record Entry(AuditLog log, String userName) {
	}

	private final AuditLogRepository audit;
	private final UserNames userNames;

	/**
	 * @param tableName filtro opcional; precisa ser uma das tabelas auditadas
	 * @param recordId filtro opcional; exige {@code tableName}
	 * @throws BusinessException {@code validation} (400) para {@code recordId} sem {@code tableName} ou tabela desconhecida
	 */
	@Transactional(readOnly = true)
	public Page<Entry> list(String tableName, UUID recordId, int page, int size) {
		String table = tableName == null || tableName.isBlank() ? null : tableName.strip();
		if (recordId != null && table == null) {
			throw new BusinessException(ProblemTypes.VALIDATION, "O filtro recordId exige tableName.");
		}
		if (table != null && !TABLES.contains(table)) {
			throw new BusinessException(ProblemTypes.VALIDATION, "tableName desconhecido.");
		}
		Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
		Page<AuditLog> logs = table == null ? audit.findAllBy(pageable)
				: recordId == null ? audit.findByTableName(table, pageable)
						: audit.findByTableNameAndRecordId(table, recordId, pageable);

		List<UUID> userIds = logs.getContent().stream().map(AuditLog::getUserId).distinct().toList();
		Map<UUID, String> names = userNames.namesOf(userIds);
		return logs.map(log -> new Entry(log, names.get(log.getUserId())));
	}
}
