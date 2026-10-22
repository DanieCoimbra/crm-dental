package crm_data.record.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import crm_data.record.domain.AuditLog;

/**
 * Somente leitura: estende {@link Repository} (e não {@code JpaRepository}), então não existe {@code save} nem
 * {@code delete}; só as consultas abaixo. A ordem ({@code occurredAt DESC, id DESC}) vem do {@link Pageable}.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

	/** Toda a trilha da clínica do tenant (filtro + RLS). */
	Page<AuditLog> findAllBy(Pageable pageable);

	Page<AuditLog> findByTableName(String tableName, Pageable pageable);

	Page<AuditLog> findByTableNameAndRecordId(String tableName, UUID recordId, Pageable pageable);
}
