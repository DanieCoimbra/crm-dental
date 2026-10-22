package crm_data.record.domain;

/** Ação registrada na trilha (coluna {@code action}, constraint {@code ck_audit_action}). */
public enum AuditAction {
	INSERT, UPDATE, DELETE, ANONYMIZE
}
