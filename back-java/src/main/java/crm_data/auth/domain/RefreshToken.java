package crm_data.auth.domain;

import java.time.Instant;
import java.util.UUID;

import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Refresh token opaco de uso único. Só o SHA-256 (hex) do token fica no banco. Os campos de estado
 * ({@code used_at}, {@code revoked_at}) são alterados por consultas atômicas do repositório.
 */
@Entity
@Table(name = "refresh_tokens", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends TenantAwareEntity {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	public RefreshToken(UUID id, UUID userId, UUID familyId, String tokenHash, Instant expiresAt) {
		this.id = id;
		this.userId = userId;
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}
}
