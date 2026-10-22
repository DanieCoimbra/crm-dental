package crm_data.record.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Linha da trilha de auditoria: só identificadores, nunca valores de colunas. Somente leitura: {@code @Immutable}, sem
 * setters e sem construtor público; quem a grava são os triggers e a função de anonimização do banco (o {@code crm_app}
 * nem tem {@code INSERT} nesta tabela). A trilha é por registro: não há {@code patient_id}.
 */
@Entity
@Immutable
@Table(name = "audit_log", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog extends TenantAwareEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "table_name", nullable = false, updatable = false, length = 30)
	private String tableName;

	@Column(name = "record_id", nullable = false, updatable = false)
	private UUID recordId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 10)
	private AuditAction action;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;
}
