package crm_data.record.web;

import java.time.Instant;
import java.util.UUID;

import crm_data.record.service.AuditQueryService.Entry;

/** Item da trilha: só identificadores e o nome do usuário; nunca valores de colunas nem {@code clinicId}. */
public record AuditEntryResponse(Long id, UUID userId, String userName, String tableName, UUID recordId, String action,
		Instant occurredAt) {

	public static AuditEntryResponse from(Entry entry) {
		var log = entry.log();
		return new AuditEntryResponse(log.getId(), log.getUserId(), entry.userName(), log.getTableName(), log.getRecordId(),
				log.getAction().name(), log.getOccurredAt());
	}

	/** Nunca imprime o nome do usuário. */
	@Override
	public String toString() {
		return "AuditEntryResponse[id=" + id + "]";
	}
}
